//! Gossip module for ConvoyRun Mobile - topic subscription and event parsing
//!
//! This module handles receiving gossip events from the convoy topic.
//! Events are received, parsed, and passed to Kotlin for persistence and re-broadcast.

use serde::{Deserialize, Serialize};
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
    #[serde(rename = "delete_convoy")]
    DeleteConvoy {
        convoy_id: String,
        peer_id: String,
        signature: String,
    },
    #[serde(rename = "channel")]
    Channel { data: String },
    #[serde(rename = "blacklist")]
    Blacklist { data: String },
    #[serde(rename = "trustlist")]
    Trustlist { data: String },
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
    serde_json::from_str(json).ok()
}

/// Verify the ed25519 signature of a convoy event JSON.
/// Returns true if the signature is valid, false otherwise.
pub fn verify_convoy_signature(convoy_json: &str) -> bool {
    use base64::Engine;
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

    let peer_id_b64 = match obj.get("peerId").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    // Clear signature for canonical form
    obj.insert("signature".to_string(), serde_json::Value::String(String::new()));

    // Remove 'deleted' field if present — desktop signs without it (#[serde(skip)])
    obj.remove("deleted");

    let canonical = canonical_json(&value);

    // Decode peer_id — supports both hex (desktop format) and base64
    let peer_id_bytes = if peer_id_b64.len() == 64 && peer_id_b64.chars().all(|c| c.is_ascii_hexdigit()) {
        // Hex format (desktop)
        match hex::decode(&peer_id_b64) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    } else {
        // Base64 format
        match base64::engine::general_purpose::STANDARD.decode(&peer_id_b64) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    };

    // Decode signature
    let sig_bytes = match base64::engine::general_purpose::STANDARD.decode(&signature_b64) {
        Ok(b) if b.len() == 64 => b,
        _ => return false,
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
pub const SCHEMA_VOTE: &str = "convoyrun/vote/v1";

/// Canonical JSON for a vote record (without signature field)
fn vote_canonical_json(obj: &serde_json::Map<String, serde_json::Value>) -> String {
    let mut clone = obj.clone();
    clone.insert("signature".to_string(), serde_json::Value::String(String::new()));
    let value = serde_json::Value::Object(clone);
    canonical_json(&value)
}

/// Sign a vote and return the serialized VoteRecord JSON.
///
/// Reads the secret key from `{data_dir}/node_identity.key`, creates a VoteRecord,
/// signs it with ed25519, and returns the complete JSON string.
pub fn sign_vote(data_dir: &str, convoy_id: String, vote: i32) -> Result<String, String> {
    use base64::Engine;
    use ed25519_dalek::{Signer, SigningKey};

    if vote != 1 && vote != -1 {
        return Err("Vote must be 1 or -1".to_string());
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
    let peer_id = hex::encode(signing_key.verifying_key().to_bytes());

    // Build VoteRecord
    let ts = chrono::Utc::now().timestamp();
    let mut obj = serde_json::Map::new();
    obj.insert("schema".to_string(), serde_json::Value::String(SCHEMA_VOTE.to_string()));
    obj.insert("convoyId".to_string(), serde_json::Value::String(convoy_id));
    obj.insert("voterPeerId".to_string(), serde_json::Value::String(peer_id));
    obj.insert("vote".to_string(), serde_json::Value::Number(vote.into()));
    obj.insert("ts".to_string(), serde_json::Value::Number(ts.into()));
    obj.insert("signature".to_string(), serde_json::Value::String(String::new()));

    // Canonical JSON for signing
    let canonical = vote_canonical_json(&obj);

    // Sign
    let signature = signing_key.sign(canonical.as_bytes());
    let sig_b64 = base64::engine::general_purpose::STANDARD.encode(signature.to_bytes());

    obj.insert("signature".to_string(), serde_json::Value::String(sig_b64));

    serde_json::to_string(&serde_json::Value::Object(obj))
        .map_err(|e| format!("Failed to serialize vote: {}", e))
}

/// Verify the ed25519 signature of a vote record JSON.
/// Returns true if the signature is valid.
pub fn verify_vote_signature(vote_json: &str) -> bool {
    use base64::Engine;
    use ed25519_dalek::{Verifier, VerifyingKey};

    let mut value: serde_json::Value = match serde_json::from_str(vote_json) {
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

    let voter_peer_id = match obj.get("voterPeerId").and_then(|v| v.as_str()) {
        Some(s) if !s.is_empty() => s.to_string(),
        _ => return false,
    };

    // Verify vote value
    let vote_val = obj.get("vote").and_then(|v| v.as_i64()).unwrap_or(0);
    if vote_val != 1 && vote_val != -1 {
        return false;
    }

    // Decode peer_id — supports hex (64 chars) and base64
    let peer_id_bytes = if voter_peer_id.len() == 64 && voter_peer_id.chars().all(|c| c.is_ascii_hexdigit()) {
        match hex::decode(&voter_peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    } else {
        match base64::engine::general_purpose::STANDARD.decode(&voter_peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    };

    // Decode signature
    let sig_bytes = match base64::engine::general_purpose::STANDARD.decode(&signature_b64) {
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

/// Verify the ed25519 signature of a blacklist record JSON.
/// Returns true if the signature is valid.
pub fn verify_blacklist_signature(blacklist_json: &str) -> bool {
    use base64::Engine;
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

    // Decode peer_id — supports hex (64 chars) and base64
    let peer_id_bytes = if author_peer_id.len() == 64 && author_peer_id.chars().all(|c| c.is_ascii_hexdigit()) {
        match hex::decode(&author_peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    } else {
        match base64::engine::general_purpose::STANDARD.decode(&author_peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    };

    // Decode signature
    let sig_bytes = match base64::engine::general_purpose::STANDARD.decode(&signature_b64) {
        Ok(b) if b.len() == 64 => b,
        _ => return false,
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
/// The signed message format is "{convoy_id}:{peer_id}" (matches desktop convoy.rs:1125-1127).
pub fn verify_delete_signature(peer_id: &str, convoy_id: &str, signature_b64: &str) -> bool {
    use base64::Engine;
    use ed25519_dalek::{Verifier, VerifyingKey};

    // Decode peer_id — supports hex (64 chars) and base64
    let peer_id_bytes = if peer_id.len() == 64 && peer_id.chars().all(|c| c.is_ascii_hexdigit()) {
        match hex::decode(peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    } else {
        match base64::engine::general_purpose::STANDARD.decode(peer_id) {
            Ok(b) if b.len() == 32 => b,
            _ => return false,
        }
    };

    // Decode signature
    let sig_bytes = match base64::engine::general_purpose::STANDARD.decode(signature_b64) {
        Ok(b) if b.len() == 64 => b,
        _ => return false,
    };

    // Reconstruct the signed message
    let message = format!("{}:{}", convoy_id, peer_id);

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
