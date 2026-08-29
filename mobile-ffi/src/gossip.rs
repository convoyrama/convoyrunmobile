//! Gossip module for ConvoyRun Mobile - topic subscription and event parsing
//!
//! This module handles receiving gossip events from the convoy topic.
//! It is read-only: it receives events but does not publish them.

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
    /// Returns None if the receiver is closed or errors.
    pub async fn next_event(&self) -> Option<GossipEvent> {
        let mut receiver = self.receiver.lock().await;

        // GossipReceiver.next() returns Result<Event, ChannelError>
        match receiver.next().await {
            Ok(event) => {
                match event {
                    iroh_gossip::api::Event::Received(message) => {
                        let sender = message.delivered_from.to_string();
                        let content = String::from_utf8_lossy(&message.content).to_string();
                        let timestamp = chrono::Utc::now().timestamp();

                        Some(GossipEvent {
                            sender,
                            content,
                            timestamp,
                        })
                    }
                    _ => {
                        // Other events (NeighborUp, NeighborDown, etc.) - skip
                        None
                    }
                }
            }
            Err(e) => {
                eprintln!("[Gossip] Error receiving message: {:?}", e);
                None
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

/// Extract convoy data from a GossipMessage (for read-only mobile app)
/// Only returns data for "convoy" type messages
pub fn extract_convoy_data(message: &GossipMessage) -> Option<&str> {
    match message {
        GossipMessage::Convoy { data } => Some(data),
        _ => None,
    }
}
