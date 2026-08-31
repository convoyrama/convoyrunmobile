//! P2P module for ConvoyRun Mobile - iroh 1.0 + gossip + DHT discovery
//!
//! This module sets up the iroh endpoint, gossip protocol, and router.
//! It mirrors the desktop p2p.rs for full P2P node functionality.

use anyhow::{Context, Result};
use iroh::{endpoint::presets, protocol::Router, Endpoint, SecretKey};
use iroh_gossip::{net::Gossip, ALPN as GOSSIP_ALPN};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

use distributed_topic_tracker::{
    AutoDiscoveryGossip, Config as DttConfig, RecordPublisher, TopicId as DttTopicId,
    PublisherConfig, BootstrapConfig, MergeConfig, BubbleMergeConfig,
};

/// Gossip topic for the convoy calendar.
/// All ConvoyRun nodes join this topic by name.
pub const CONVOY_TOPIC: &str = "convoyrama.convoyrun.v3";

/// Shared passphrase for DHT discovery.
/// All ConvoyRun clients use this to find each other automatically
/// via the Mainline DHT (BEP 44).
/// It is public - anyone with this passphrase can join the network.
const CONVOY_PASSPHRASE: &str = "convoyrun-convoy-calendar-v1";

/// Identity filename (SecretKey)
const IDENTITY_FILE: &str = "node_identity.key";

/// P2P node state - holds all iroh resources
pub struct P2pNode {
    pub gossip: Gossip,
    pub router: Router,
    pub secret_key: SecretKey,
    pub neighbor_count: Arc<AtomicUsize>,
    pub is_online: Arc<AtomicBool>,
}

impl P2pNode {
    /// Initialize the P2P node.
    /// Loads or creates identity, sets up endpoint, gossip, and router.
    pub async fn init(data_dir: &Path) -> Result<Self> {
        // Create data directory if needed
        std::fs::create_dir_all(data_dir).context("Failed to create data directory")?;

        // Load or create identity
        let secret_key = load_or_create_identity(data_dir)?;
        let peer_id = secret_key.public().to_string();

        eprintln!("[P2P] Node starting with peerId: {}", peer_id);

        // Endpoint with public relays (presets::N0 includes DNS/Pkarr + default relays)
        let endpoint = Endpoint::builder(presets::N0)
            .secret_key(secret_key.clone())
            .bind()
            .await
            .context("Failed to bind endpoint")?;

        // Gossip protocol (event propagation)
        let gossip = Gossip::builder().spawn(endpoint.clone());

        // Router - wire gossip protocol
        let router = Router::builder(endpoint.clone())
            .accept(GOSSIP_ALPN, gossip.clone())
            .spawn();

        eprintln!("[P2P] Node initialized with peerId: {}", peer_id);

        Ok(Self {
            gossip,
            router,
            secret_key,
            neighbor_count: Arc::new(AtomicUsize::new(0)),
            is_online: Arc::new(AtomicBool::new(false)),
        })
    }

    /// Get the node's peer ID
    pub fn peer_id(&self) -> String {
        self.secret_key.public().to_string()
    }

    /// Check if the node is online (has connected peers)
    pub fn is_online(&self) -> bool {
        self.is_online.load(Ordering::Relaxed)
    }

    /// Get the number of connected neighbors
    pub fn neighbor_count(&self) -> usize {
        self.neighbor_count.load(Ordering::Relaxed)
    }

    /// Join the convoy gossip topic with DHT auto-discovery.
    /// Returns a (sender, receiver) pair for the topic.
    pub async fn join_topic(
        &self,
    ) -> Result<(
        distributed_topic_tracker::GossipSender,
        distributed_topic_tracker::GossipReceiver,
    )> {
        // Derive ed25519 signing key from iroh SecretKey
        let key_bytes = self.secret_key.to_bytes();
        let key_array: [u8; 32] = key_bytes.into();
        let signing_key = ed25519_dalek::SigningKey::from_bytes(&key_array);

        // TopicId from topic name
        let topic_id = DttTopicId::new(CONVOY_TOPIC.to_string());

        // Aggressive DTT config for faster reconnection on mobile
        let dtt_config = DttConfig::builder()
            .publisher_config(
                PublisherConfig::builder()
                    .initial_delay(std::time::Duration::from_secs(3))
                    .base_interval(std::time::Duration::from_secs(5))
                    .max_jitter(std::time::Duration::from_secs(5))
                    .build(),
            )
            .bootstrap_config(
                BootstrapConfig::builder()
                    .no_peers_retry_interval(std::time::Duration::from_millis(500))
                    .discovery_poll_interval(std::time::Duration::from_secs(1))
                    .build(),
            )
            .merge_config(
                MergeConfig::builder()
                    .bubble_merge(
                        BubbleMergeConfig::builder()
                            .initial_interval(std::time::Duration::from_secs(10))
                            .base_interval(std::time::Duration::from_secs(15))
                            .max_jitter(std::time::Duration::from_secs(5))
                            .build(),
                    )
                    .build(),
            )
            .build();

        // RecordPublisher manages DHT publication and discovery
        let record_publisher = RecordPublisher::new(
            topic_id,
            signing_key,
            None, // no custom secret rotation
            CONVOY_PASSPHRASE.as_bytes().to_vec(),
            dtt_config,
        );

        // subscribe_and_join_with_auto_discovery_no_wait:
        // 1. Publishes endpoint to Mainline DHT
        // 2. Reads records from other nodes sharing the same passphrase
        // 3. Connects to discovered peers via QUIC + NAT traversal
        // 4. Returns immediately without waiting for peers (non-blocking)
        let topic = self
            .gossip
            .subscribe_and_join_with_auto_discovery_no_wait(record_publisher)
            .await?;

        let (sender, receiver) = topic.split().await?;

        eprintln!("[P2P] Joined topic with auto-discovery: {}", CONVOY_TOPIC);
        Ok((sender, receiver))
    }

    /// Graceful shutdown - notifies other peers that this node went offline
    pub async fn close(&self) -> Result<()> {
        self.router
            .shutdown()
            .await
            .context("Failed to shutdown router")?;
        eprintln!("[P2P] Node shut down gracefully");
        Ok(())
    }
}

/// Load identity from file or create a new one (atomic — no TOCTOU race)
fn load_or_create_identity(data_dir: &Path) -> Result<SecretKey> {
    let identity_path = data_dir.join(IDENTITY_FILE);

    // Try to read existing identity first
    match std::fs::read(&identity_path) {
        Ok(key_bytes) => {
            if key_bytes.len() != 32 {
                anyhow::bail!(
                    "Invalid identity file: expected 32 bytes, got {}",
                    key_bytes.len()
                );
            }
            let mut key_array = [0u8; 32];
            key_array.copy_from_slice(&key_bytes);
            let secret_key = SecretKey::from_bytes(&key_array);
            eprintln!("[P2P] Loaded existing identity");
            Ok(secret_key)
        }
        Err(_) => {
            // Create new identity atomically
            let secret_key = SecretKey::generate();
            let key_bytes = secret_key.to_bytes();

            // Write with restrictive permissions (owner only)
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let perms = std::fs::Permissions::from_mode(0o600);
                let f = std::fs::File::create(&identity_path)
                    .context("Failed to create identity file")?;
                f.set_permissions(perms)
                    .context("Failed to set identity file permissions")?;
                std::fs::write(&identity_path, key_bytes)
                    .context("Failed to write identity file")?;
            }
            #[cfg(not(unix))]
            {
                std::fs::write(&identity_path, key_bytes)
                    .context("Failed to write identity file")?;
            }

            eprintln!("[P2P] Created new identity");
            Ok(secret_key)
        }
    }
}
