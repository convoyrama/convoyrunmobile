//! Gossip module for ConvoyRun Mobile - topic subscription and event parsing
//!
//! This module handles receiving gossip events from the convoy topic.
//! Events are received, parsed, and passed to Kotlin for persistence and re-broadcast.

use serde::{Deserialize, Serialize};
use base64::Engine;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use tokio::sync::Mutex;

/// Re-export the receiver type from distributed-topic-tracker
pub type GossipReceiver = distributed_topic_tracker::GossipReceiver;

/// Gossip message types (matches desktop GossipMessage enum)
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum GossipMessage {
    #[serde(rename = "convoy")]
    Convoy { data: String },
    #[serde(rename = "vote")]
    Vote { data: String },
    #[serde(rename = "profile")]
    Profile { data: String },
    #[serde(rename = "tombstone")]
    Tombstone {
        convoy_id: String,
        peer_id: String,
        revision: u64,
        signature: String,
    },
    #[serde(rename = "channel")]
    Channel { data: String },
    #[serde(rename = "blacklist")]
    Blacklist { data: String },
    #[serde(rename = "trustlist")]
    Trustlist { data: String },
}
#[cfg(test)]
mod ctes_fixture_tests {
    use super::{canonical_json, parse_gossip_message, sign_profile, verify_profile_signature, verify_vote_signature};
    use base64::Engine as _;
    use ed25519_dalek::{Verifier, VerifyingKey};
    use std::fs;

    const VECTORS: &str = include_str!("../tests/fixtures/ctes-v1/vectors.json");
    const DOCUMENTS: [(&str, &str); 5] = [
        ("event", include_str!("../tests/fixtures/ctes-v1/event.json")),
        ("profile", include_str!("../tests/fixtures/ctes-v1/profile.json")),
        ("voteUp", include_str!("../tests/fixtures/ctes-v1/vote-up-r1.json")),
        ("voteDown", include_str!("../tests/fixtures/ctes-v1/vote-down-r2.json")),
        ("tombstone", include_str!("../tests/fixtures/ctes-v1/tombstone-r2.json")),
    ];

    #[test]
    fn verifies_ctes_signed_fixtures() {
        let vectors: serde_json::Value = serde_json::from_str(VECTORS).unwrap();
        let author_id = vectors["testKey"]["authorId"].as_str().unwrap();
        let public_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .decode(author_id.strip_prefix("ed25519:").unwrap())
            .unwrap();
        let public_key: [u8; 32] = public_bytes.try_into().unwrap();
        let verifying_key = VerifyingKey::from_bytes(&public_key).unwrap();

        for (name, source) in DOCUMENTS {
            let mut document: serde_json::Value = serde_json::from_str(source).unwrap();
            let signature_text = document
                .as_object_mut()
                .unwrap()
                .remove("signature")
                .unwrap()
                .as_str()
                .unwrap()
                .to_string();
            let canonical = canonical_json(&document);
            assert_eq!(canonical, vectors["documents"][name]["canonical"]);
            assert_eq!(signature_text, vectors["documents"][name]["signature"]);

            let signature_bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD
                .decode(signature_text)
                .unwrap();
            let signature_array: [u8; 64] = signature_bytes.try_into().unwrap();
            let signature = ed25519_dalek::Signature::from_bytes(&signature_array);
            verifying_key.verify(canonical.as_bytes(), &signature).unwrap();
        }
    }

    #[test]
    fn accepts_ctes_vote_fixtures() {
        assert!(verify_vote_signature(DOCUMENTS[2].1));
        assert!(verify_vote_signature(DOCUMENTS[3].1));
    }

    #[test]
    fn accepts_ctes_profile_fixture() {
        assert!(verify_profile_signature(DOCUMENTS[1].1));
    }

    #[test]
    fn sign_profile_roundtrip_produces_valid_profile() {
        let vectors: serde_json::Value = serde_json::from_str(VECTORS).unwrap();
        let seed_hex = vectors["testKey"]["seedHex"].as_str().unwrap();
        let key_bytes = hex::decode(seed_hex).unwrap();
        let temp_dir = std::env::temp_dir().join(format!(
            "convoyrun-profile-test-{}",
            std::process::id()
        ));
        fs::create_dir_all(&temp_dir).unwrap();
        fs::write(temp_dir.join("node_identity.key"), &key_bytes).unwrap();

        let profile_json = sign_profile(
            temp_dir.to_str().unwrap(),
            "Ruta Ñ 2".to_string(),
            2,
            Some("2026-09-04T15:10:00Z".to_string()),
        )
        .unwrap();

        assert!(verify_profile_signature(&profile_json));

        let profile: serde_json::Value = serde_json::from_str(&profile_json).unwrap();
        assert_eq!(profile["kind"], "profile");
        assert_eq!(profile["revision"], 2);
        assert_eq!(profile["data"]["nickname"], "Ruta Ñ 2");

        let _ = fs::remove_dir_all(&temp_dir);
    }

    #[test]
    fn parses_and_publishes_ctes_envelopes_for_core_documents() {
        for (kind, source) in [
            ("event", include_str!("../../../../ctes/fixtures/valid/event.json")),
            ("profile", include_str!("../../../../ctes/fixtures/valid/profile.json")),
            ("vote", include_str!("../../../../ctes/fixtures/valid/vote-up-r1.json")),
            ("tombstone", include_str!("../../../../ctes/fixtures/valid/tombstone-r2.json")),
        ] {
            let envelope = serde_json::json!({
                "protocol": "ctes-gossip/1",
                "type": "document",
                "document": serde_json::from_str::<serde_json::Value>(source).unwrap(),
            })
            .to_string();
            let message = parse_gossip_message(&envelope).expect("expected CTES envelope");

            match (kind, message) {
                ("event", super::GossipMessage::Convoy { data })
                | ("profile", super::GossipMessage::Profile { data })
                | ("vote", super::GossipMessage::Vote { data }) => {
                    let document: serde_json::Value = serde_json::from_str(&data).unwrap();
                    assert_eq!(document["kind"], kind);
                }
                ("tombstone", super::GossipMessage::Tombstone { convoy_id, peer_id, revision, signature }) => {
                    let document: serde_json::Value = serde_json::from_str(source).unwrap();
                    assert_eq!(convoy_id, document["eventId"].as_str().unwrap());
                    assert_eq!(peer_id, document["authorId"].as_str().unwrap());
                    assert_eq!(revision, document["revision"].as_u64().unwrap());
                    assert_eq!(signature, document["signature"].as_str().unwrap());
                }
                other => panic!("unexpected gossip variant: {:?}", other),
            }
        }
    }
}

/// A received gossip event with metadata
#[derive(Debug, Clone)]
pub struct GossipEvent {
    /// Peer ID of the sender (base64-encoded ed25519 public key)
    pub sender: String,
    /// Raw JSON content of the gossip message
    pub content: String,
    /// Unix timestamp when the message was received
    pub timestamp: i64,
}

/// Gossip subscription - receives events from the convoy topic
pub struct GossipSubscription {
    receiver: Mutex<distributed_topic_tracker::GossipReceiver>,
    neighbor_count: Arc<AtomicUsize>,
    is_online: Arc<AtomicBool>,
}

impl GossipSubscription {
    /// Create a new subscription from a gossip receiver
    pub fn new(
        receiver: distributed_topic_tracker::GossipReceiver,
        neighbor_count: Arc<AtomicUsize>,
        is_online: Arc<AtomicBool>,
    ) -> Self {
        Self {
            receiver: Mutex::new(receiver),
            neighbor_count,
            is_online,
        }
    }

    /// Wait for the next gossip event.
    /// Loops internally over control events (NeighborUp/Down).
    /// Returns None ONLY when the receiver channel is truly closed.
    pub async fn next_event(&self) -> Option<GossipEvent> {
        eprintln!("[Gossip] next_event: acquiring lock...");
        let mut receiver = self.receiver.lock().await;
        eprintln!("[Gossip] next_event: lock acquired, waiting for event...");

        loop {
            match receiver.next().await {
                Ok(event) => {
                    match event {
                        iroh_gossip::api::Event::Received(message) => {
                            eprintln!("[Gossip] Received message: {} bytes from {}", message.content.len(), message.delivered_from);
                            if message.content.len() > 1024 * 1024 {
                                eprintln!("[Gossip] Dropping oversized message ({} bytes)", message.content.len());
                                continue;
                            }
                            let sender = message.delivered_from.to_string();
                            let content = String::from_utf8_lossy(&message.content).to_string();
                            let timestamp = chrono::Utc::now().timestamp();
                            eprintln!("[Gossip] Received message from {} ({} bytes)", sender, content.len());

                            return Some(GossipEvent {
                                sender,
                                content,
                                timestamp,
                            });
                        }
                        iroh_gossip::api::Event::NeighborUp(peer) => {
                            let count = self.neighbor_count.fetch_add(1, Ordering::Relaxed) + 1;
                            self.is_online.store(true, Ordering::Relaxed);
                            eprintln!("[Gossip] NeighborUp: {} (total: {})", peer, count);
                            continue;
                        }
                        iroh_gossip::api::Event::NeighborDown(peer) => {
                            self.neighbor_count.fetch_update(
                                Ordering::Relaxed,
                                Ordering::Relaxed,
                                |prev| if prev > 0 { Some(prev - 1) } else { None },
                            ).ok();
                            let count = self.neighbor_count.load(Ordering::Relaxed);
                            if count == 0 {
                                self.is_online.store(false, Ordering::Relaxed);
                            }
                            eprintln!("[Gossip] NeighborDown: {} (total: {})", peer, count);
                            continue;
                        }
                        other => {
                            eprintln!("[Gossip] Other event: {:?}", std::mem::discriminant(&other));
                            continue;
                        }
                    }
                }
                Err(e) => {
                    eprintln!("[Gossip] Receiver closed: {:?}", e);
                    return None;
                }
            }
        }
    }

    /// Get the number of connected peers
    pub fn peer_count(&self) -> u32 {
        self.neighbor_count.load(Ordering::Relaxed) as u32
    }

    /// Check if the node is online
    pub fn is_online(&self) -> bool {
        self.is_online.load(Ordering::Relaxed)
    }
}

/// Parse a GossipMessage from JSON string
pub fn parse_gossip_message(json: &str) -> Option<GossipMessage> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    let obj = value.as_object()?;
    if obj.get("protocol").and_then(|value| value.as_str()) == Some("ctes-gossip/1")
        && obj.get("type").and_then(|value| value.as_str()) == Some("document")
    {
        let document = obj.get("document")?.clone();
        return match document.get("kind").and_then(|value| value.as_str())? {
            "event" => Some(GossipMessage::Convoy { data: document.to_string() }),
            "vote" => Some(GossipMessage::Vote { data: document.to_string() }),
            "profile" => Some(GossipMessage::Profile { data: document.to_string() }),
            "tombstone" => {
                let convoy_id = document.get("eventId").and_then(|value| value.as_str())?.to_string();
                let peer_id = document.get("authorId").and_then(|value| value.as_str())?.to_string();
                let revision = document.get("revision").and_then(|value| value.as_u64())?;
                let signature = document.get("signature").and_then(|value| value.as_str())?.to_string();
                Some(GossipMessage::Tombstone { convoy_id, peer_id, revision, signature })
            }
            _ => None,
        };
    }
    None
}

fn decode_peer_id_bytes(peer_id: &str) -> Option<[u8; 32]> {
    use base64::Engine;

    let trimmed = peer_id.strip_prefix("ed25519:").unwrap_or(peer_id);
    if trimmed.len() == 64 && trimmed.chars().all(|c| c.is_ascii_hexdigit()) {
        return hex::decode(trimmed).ok()?.try_into().ok();
    }
    let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(trimmed)
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(trimmed))
        .ok()?;
    decoded.try_into().ok()
}

fn decode_signature_bytes(signature_b64: &str) -> Option<[u8; 64]> {
    let decoded = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(signature_b64)
        .or_else(|_| base64::engine::general_purpose::STANDARD.decode(signature_b64))
        .ok()?;
    decoded.try_into().ok()
}

/// Verify the ed25519 signature of a convoy event JSON.
/// Returns true if the signature is valid, false otherwise.
pub fn verify_convoy_signature(convoy_json: &str) -> bool {
    use ed25519_dalek::{Verifier, VerifyingKey};

    let mut value: serde_json::Value = match serde_json::from_str(convoy_json) {
        Ok(v) => v,
        Err(_) => return false,
    };

    let obj = match value.as_object_mut() {
        Some(o) => o,
        None => return false,
    };

    let signature_b64 = match obj.get("signature").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    let peer_id_b64 = match obj.get("authorId").and_then(|v| v.as_str())
        .or_else(|| obj.get("peerId").and_then(|v| v.as_str())) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    // Clear signature for canonical form
    obj.insert("signature".to_string(), serde_json::Value::String(String::new()));

    // Strip local tombstone metadata from the signed payload; it is persisted locally
    // but excluded from the canonical event signature.
    obj.remove("deleted");
    obj.remove("deleteSignature");
    if obj.get("revision").and_then(|v| v.as_u64()) == Some(1) {
        obj.remove("revision");
    }

    let canonical = canonical_json(&value);

    let peer_id_bytes = match decode_peer_id_bytes(&peer_id_b64) {
        Some(bytes) => bytes,
        None => return false,
    };

    let sig_bytes = match decode_signature_bytes(&signature_b64) {
        Some(bytes) => bytes,
        None => return false,
    };

    let mut key_array = [0u8; 32];
    key_array.copy_from_slice(&peer_id_bytes);
    let verifying_key = match VerifyingKey::from_bytes(&key_array) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let mut sig_array = [0u8; 64];
    sig_array.copy_from_slice(&sig_bytes);
    let signature = ed25519_dalek::Signature::from_bytes(&sig_array);

    verifying_key.verify(canonical.as_bytes(), &signature).is_ok()
}

/// Vote record schema
pub const CTES_VERSION: &str = "1.0";

fn is_uuid_v7(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 36
        && bytes[8] == b'-'
        && bytes[13] == b'-'
        && bytes[18] == b'-'
        && bytes[23] == b'-'
        && bytes[14] == b'7'
        && matches!(bytes[19], b'8' | b'9' | b'a' | b'b')
        && bytes.iter().enumerate().all(|(index, byte)| {
            matches!(index, 8 | 13 | 18 | 23) || byte.is_ascii_digit() || matches!(byte, b'a'..=b'f')
        })
}

/// Canonical JSON for a vote record (without signature field)
fn vote_canonical_json(obj: &serde_json::Map<String, serde_json::Value>) -> String {
    let mut clone = obj.clone();
    clone.remove("signature");
    let value = serde_json::Value::Object(clone);
    canonical_json(&value)
}

/// Sign a vote and return the serialized VoteRecord JSON.
///
/// Reads the secret key from `{data_dir}/node_identity.key`, creates a VoteRecord,
/// signs it with ed25519, and returns the complete JSON string.
pub fn sign_vote(
    data_dir: &str,
    event_id: String,
    vote: i32,
    revision: u64,
    created_at: Option<String>,
) -> Result<String, String> {
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};

    if !matches!(vote, -1..=1) {
        return Err("Vote must be -1, 0 or 1".to_string());
    }
    if !is_uuid_v7(&event_id) || !(1..=9_007_199_254_740_991).contains(&revision) {
        return Err("Invalid CTES vote identity or revision".to_string());
    }

    // Load secret key
    let identity_path = std::path::Path::new(data_dir).join("node_identity.key");
    let key_bytes = std::fs::read(&identity_path)
        .map_err(|e| format!("Failed to read identity: {}", e))?;

    if key_bytes.len() != 32 {
        return Err(format!("Invalid identity: expected 32 bytes, got {}", key_bytes.len()));
    }

    let mut key_array = [0u8; 32];
    key_array.copy_from_slice(&key_bytes);
    let signing_key = SigningKey::from_bytes(&key_array);
    let author_id = format!(
        "ed25519:{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(signing_key.verifying_key().to_bytes())
    );

    // Build VoteRecord
    let now = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    let mut obj = serde_json::Map::new();
    obj.insert("specVersion".to_string(), serde_json::Value::String(CTES_VERSION.to_string()));
    obj.insert("kind".to_string(), serde_json::Value::String("vote".to_string()));
    obj.insert("eventId".to_string(), serde_json::Value::String(event_id));
    obj.insert("revision".to_string(), serde_json::Value::Number(revision.into()));
    obj.insert("authorId".to_string(), serde_json::Value::String(author_id));
    obj.insert("createdAt".to_string(), serde_json::Value::String(created_at.unwrap_or_else(|| now.clone())));
    obj.insert("updatedAt".to_string(), serde_json::Value::String(now));
    obj.insert("data".to_string(), serde_json::json!({ "value": vote }));

    // Canonical JSON for signing
    let canonical = vote_canonical_json(&obj);

    // Sign
    let signature = signing_key.sign(canonical.as_bytes());
    let sig_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(signature.to_bytes());

    obj.insert("signature".to_string(), serde_json::Value::String(sig_b64));

    serde_json::to_string(&serde_json::Value::Object(obj))
        .map_err(|e| format!("Failed to serialize vote: {}", e))
}

/// Verify the ed25519 signature of a vote record JSON.
/// Returns true if the signature is valid.
pub fn verify_vote_signature(vote_json: &str) -> bool {
    use base64::Engine;
    use ed25519_dalek::{Verifier, VerifyingKey};

    if vote_json.len() > 262_144 {
        return false;
    }
    let mut value: serde_json::Value = match serde_json::from_str(vote_json) {
        Ok(v) => v,
        Err(_) => return false,
    };

    let obj = match value.as_object_mut() {
        Some(o) => o,
        None => return false,
    };

    const FIELDS: [&str; 9] = [
        "specVersion", "kind", "eventId", "revision", "authorId", "createdAt",
        "updatedAt", "data", "signature",
    ];
    if obj.len() != FIELDS.len() || !FIELDS.iter().all(|field| obj.contains_key(*field)) {
        return false;
    }
    if !obj.get("data").and_then(|data| data.as_object())
        .is_some_and(|data| data.len() == 1 && data.contains_key("value")) {
        return false;
    }

    let signature_b64 = match obj.get("signature").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    if obj.get("specVersion").and_then(|v| v.as_str()) != Some(CTES_VERSION)
        || obj.get("kind").and_then(|v| v.as_str()) != Some("vote")
        || !(1..=9_007_199_254_740_991)
            .contains(&obj.get("revision").and_then(|v| v.as_u64()).unwrap_or(0)) {
        return false;
    }

    let event_id = match obj.get("eventId").and_then(|v| v.as_str()) {
        Some(id) => id,
        None => return false,
    };
    if !is_uuid_v7(event_id) {
        return false;
    }

    let timestamps = obj.get("createdAt").and_then(|v| v.as_str()).zip(
        obj.get("updatedAt").and_then(|v| v.as_str())
    ).and_then(|(created, updated)| {
        Some((
            chrono::DateTime::parse_from_rfc3339(created).ok()?,
            chrono::DateTime::parse_from_rfc3339(updated).ok()?,
            created.ends_with('Z') && updated.ends_with('Z'),
        ))
    });
    if !timestamps.is_some_and(|(created, updated, utc)| utc && created <= updated) {
        return false;
    }

    let author_id = match obj.get("authorId").and_then(|v| v.as_str()) {
        Some(s) if s.starts_with("ed25519:") && s.len() == 51 => s,
        _ => return false,
    };

    // Verify vote value
    let vote_val = obj.get("data").and_then(|v| v.get("value")).and_then(|v| v.as_i64());
    if !matches!(vote_val, Some(-1..=1)) {
        return false;
    }

    let peer_id_bytes = match base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(&author_id[8..]) {
        Ok(b) if b.len() == 32 => b,
        _ => return false,
    };

    // Decode signature
    let sig_bytes = match base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(&signature_b64) {
        Ok(b) if b.len() == 64 => b,
        _ => return false,
    };

    // Rebuild canonical JSON
    let canonical = vote_canonical_json(obj);

    let mut key_array = [0u8; 32];
    key_array.copy_from_slice(&peer_id_bytes);
    let verifying_key = match VerifyingKey::from_bytes(&key_array) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let mut sig_array = [0u8; 64];
    sig_array.copy_from_slice(&sig_bytes);
    let signature = ed25519_dalek::Signature::from_bytes(&sig_array);

    verifying_key.verify(canonical.as_bytes(), &signature).is_ok()
}

pub fn sign_profile(
    data_dir: &str,
    nickname: String,
    revision: u64,
    created_at: Option<String>,
) -> Result<String, String> {
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};

    let nickname = nickname.trim();
    if nickname.is_empty() || nickname.chars().count() > 32
        || !(1..=9_007_199_254_740_991).contains(&revision) {
        return Err("Invalid CTES profile".to_string());
    }
    let key_bytes = std::fs::read(std::path::Path::new(data_dir).join("node_identity.key"))
        .map_err(|e| format!("Failed to read identity: {}", e))?;
    let key_array: [u8; 32] = key_bytes.try_into()
        .map_err(|_| "Invalid identity length".to_string())?;
    let signing_key = SigningKey::from_bytes(&key_array);
    let author_id = format!(
        "ed25519:{}",
        base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(signing_key.verifying_key().to_bytes())
    );
    let now = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Secs, true);
    let mut obj = serde_json::Map::new();
    obj.insert("specVersion".into(), serde_json::json!(CTES_VERSION));
    obj.insert("kind".into(), serde_json::json!("profile"));
    obj.insert("revision".into(), serde_json::json!(revision));
    obj.insert("authorId".into(), serde_json::json!(author_id));
    obj.insert("createdAt".into(), serde_json::json!(created_at.unwrap_or_else(|| now.clone())));
    obj.insert("updatedAt".into(), serde_json::json!(now));
    obj.insert("data".into(), serde_json::json!({ "nickname": nickname }));
    let canonical = vote_canonical_json(&obj);
    let signature = signing_key.sign(canonical.as_bytes());
    obj.insert(
        "signature".into(),
        serde_json::json!(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(signature.to_bytes())),
    );
    serde_json::to_string(&obj).map_err(|e| e.to_string())
}

pub fn verify_profile_signature(profile_json: &str) -> bool {
    use ed25519_dalek::{Verifier, VerifyingKey};

    if profile_json.len() > 262_144 { return false; }
    let mut value: serde_json::Value = match serde_json::from_str(profile_json) {
        Ok(value) => value,
        Err(_) => return false,
    };
    let obj = match value.as_object_mut() { Some(obj) => obj, None => return false };
    const FIELDS: [&str; 8] = [
        "specVersion", "kind", "revision", "authorId", "createdAt", "updatedAt", "data", "signature",
    ];
    if obj.len() != FIELDS.len() || !FIELDS.iter().all(|field| obj.contains_key(*field)) {
        return false;
    }
    let revision = obj.get("revision").and_then(|value| value.as_u64()).unwrap_or(0);
    if obj.get("specVersion").and_then(|value| value.as_str()) != Some(CTES_VERSION)
        || obj.get("kind").and_then(|value| value.as_str()) != Some("profile")
        || !(1..=9_007_199_254_740_991).contains(&revision) {
        return false;
    }
    let data = match obj.get("data").and_then(|value| value.as_object()) {
        Some(data) if data.len() <= 2 && data.contains_key("nickname")
            && data.keys().all(|key| key == "nickname" || key == "links") => data,
        _ => return false,
    };
    let nickname = data.get("nickname").and_then(|value| value.as_str()).unwrap_or("");
    if nickname.trim().is_empty() || nickname.chars().count() > 32 { return false; }
    let author_id = match obj.get("authorId").and_then(|value| value.as_str()) {
        Some(author) if author.len() == 51 && author.starts_with("ed25519:") => author,
        _ => return false,
    };
    let timestamps = obj.get("createdAt").and_then(|v| v.as_str()).zip(
        obj.get("updatedAt").and_then(|v| v.as_str())
    ).and_then(|(created, updated)| Some((
        chrono::DateTime::parse_from_rfc3339(created).ok()?,
        chrono::DateTime::parse_from_rfc3339(updated).ok()?,
        created.ends_with('Z') && updated.ends_with('Z'),
    )));
    if !timestamps.is_some_and(|(created, updated, utc)| utc && created <= updated) { return false; }
    let public = match decode_peer_id_bytes(author_id) {
        Some(bytes) => bytes,
        None => return false,
    };
    let signature_text = match obj.remove("signature").and_then(|value| value.as_str().map(str::to_string)) {
        Some(signature) => signature,
        None => return false,
    };
    let signature = match decode_signature_bytes(&signature_text) {
        Some(bytes) => bytes,
        None => return false,
    };
    VerifyingKey::from_bytes(&public).is_ok_and(|key| key.verify(
        canonical_json(&value).as_bytes(),
        &ed25519_dalek::Signature::from_bytes(&signature),
    ).is_ok())
}

/// Verify the ed25519 signature of a blacklist record JSON.
/// Returns true if the signature is valid.
pub fn verify_blacklist_signature(blacklist_json: &str) -> bool {
    use ed25519_dalek::{Verifier, VerifyingKey};

    let mut value: serde_json::Value = match serde_json::from_str(blacklist_json) {
        Ok(v) => v,
        Err(_) => return false,
    };

    let obj = match value.as_object_mut() {
        Some(o) => o,
        None => return false,
    };

    let signature_b64 = match obj.get("signature").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    let author_peer_id = match obj.get("authorPeerId").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    let peer_id_bytes = match decode_peer_id_bytes(&author_peer_id) {
        Some(bytes) => bytes,
        None => return false,
    };

    let sig_bytes = match decode_signature_bytes(&signature_b64) {
        Some(bytes) => bytes,
        None => return false,
    };

    // Rebuild canonical JSON
    let canonical = canonical_json(&value);

    let mut key_array = [0u8; 32];
    key_array.copy_from_slice(&peer_id_bytes);
    let verifying_key = match VerifyingKey::from_bytes(&key_array) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let mut sig_array = [0u8; 64];
    sig_array.copy_from_slice(&sig_bytes);
    let signature = ed25519_dalek::Signature::from_bytes(&sig_array);

    verifying_key.verify(canonical.as_bytes(), &signature).is_ok()
}

/// Verify the ed25519 signature of a delete convoy message.
/// The signed message format is "{convoy_id}:{peer_id}:{revision}".
pub fn verify_delete_signature(peer_id: &str, convoy_id: &str, revision: u64, signature_b64: &str) -> bool {
    use ed25519_dalek::{Verifier, VerifyingKey};

    let peer_id = peer_id.trim();
    let peer_id_bytes = match decode_peer_id_bytes(peer_id) {
        Some(bytes) => bytes,
        None => return false,
    };

    let sig_bytes = match decode_signature_bytes(signature_b64) {
        Some(bytes) => bytes,
        None => return false,
    };

    // Reconstruct the signed message
    let message = format!("{}:{}:{}", convoy_id, peer_id, revision);

    let mut key_array = [0u8; 32];
    key_array.copy_from_slice(&peer_id_bytes);
    let verifying_key = match VerifyingKey::from_bytes(&key_array) {
        Ok(k) => k,
        Err(_) => return false,
    };

    let mut sig_array = [0u8; 64];
    sig_array.copy_from_slice(&sig_bytes);
    let signature = ed25519_dalek::Signature::from_bytes(&sig_array);

    verifying_key.verify(message.as_bytes(), &signature).is_ok()
}

/// Canonical JSON serialization (sorted keys, recursive)
fn canonical_json(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::Null => "null".to_string(),
        serde_json::Value::Bool(b) => b.to_string(),
        serde_json::Value::Number(n) => n.to_string(),
        serde_json::Value::String(s) => {
            serde_json::to_string(s).unwrap_or_else(|_| "\"\"".to_string())
        }
        serde_json::Value::Array(arr) => {
            let items: Vec<String> = arr.iter().map(canonical_json).collect();
            format!("[{}]", items.join(","))
        }
        serde_json::Value::Object(obj) => {
            let mut keys: Vec<_> = obj.keys().collect();
            keys.sort();
            let items: Vec<String> = keys
                .iter()
                .map(|k| {
                    format!(
                        "{}:{}",
                        serde_json::to_string(k).unwrap_or_else(|_| "\"\"".to_string()),
                        canonical_json(&obj[*k])
                    )
                })
                .collect();
            format!("{{{}}}", items.join(","))
        }
    }
}
