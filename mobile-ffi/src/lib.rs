//! ConvoyRun Mobile FFI - UniFFI bindings for Android
//!
//! This crate provides a read-only P2P layer for ConvoyRun Mobile.
//! It connects to the same iroh gossip network as the desktop app,
//! receives convoy events, and exposes them to Kotlin via UniFFI.

mod android_init;
mod gossip;
mod p2p;

use std::sync::Arc;

uniffi::setup_scaffolding!();

/// Initialize the Android context (fallback for non-Android builds)
#[cfg(not(target_os = "android"))]
pub fn init_android_context_fallback() {
    android_init::init_android_context_fallback();
}

/// P2P node wrapper for UniFFI
#[derive(uniffi::Object)]
pub struct P2pNodeWrapper {
    inner: p2p::P2pNode,
}

/// Create a new P2P node (factory function — async constructor)
#[uniffi::export]
pub async fn create_p2p_node(data_dir: String) -> Result<Arc<P2pNodeWrapper>, P2pError> {
    let path = std::path::Path::new(&data_dir);
    let node = p2p::P2pNode::init(path)
        .await
        .map_err(|e| P2pError::InitError(e.to_string()))?;
    Ok(Arc::new(P2pNodeWrapper { inner: node }))
}

#[uniffi::export]
impl P2pNodeWrapper {
    /// Get the node's peer ID
    pub fn peer_id(&self) -> String {
        self.inner.peer_id()
    }

    /// Check if the node is online
    pub fn is_online(&self) -> bool {
        self.inner.is_online()
    }

    /// Get the number of connected peers
    pub fn neighbor_count(&self) -> u32 {
        self.inner.neighbor_count() as u32
    }

    /// Join the convoy gossip topic
    pub async fn join_topic(self: Arc<Self>) -> Result<Arc<GossipSubscriptionWrapper>, P2pError> {
        let (sender, receiver) = self
            .inner
            .join_topic()
            .await
            .map_err(|e| P2pError::JoinError(e.to_string()))?;

        let subscription = GossipSubscriptionWrapper::new(
            receiver,
            sender,
            self.inner.neighbor_count.clone(),
            self.inner.is_online.clone(),
        );

        Ok(Arc::new(subscription))
    }

    /// Graceful shutdown
    pub async fn shutdown(&self) -> Result<(), P2pError> {
        self.inner
            .close()
            .await
            .map_err(|e| P2pError::ShutdownError(e.to_string()))
    }
}

/// Gossip subscription wrapper for UniFFI
#[derive(uniffi::Object)]
pub struct GossipSubscriptionWrapper {
    inner: gossip::GossipSubscription,
    _sender: distributed_topic_tracker::GossipSender,
}

impl GossipSubscriptionWrapper {
    fn new(
        receiver: gossip::GossipReceiver,
        sender: distributed_topic_tracker::GossipSender,
        neighbor_count: Arc<std::sync::atomic::AtomicUsize>,
        is_online: Arc<std::sync::atomic::AtomicBool>,
    ) -> Self {
        Self {
            inner: gossip::GossipSubscription::new(receiver, neighbor_count, is_online),
            _sender: sender,
        }
    }
}

#[uniffi::export]
impl GossipSubscriptionWrapper {
    /// Wait for the next gossip event
    pub async fn next_event(&self) -> Option<Arc<GossipEventWrapper>> {
        self.inner
            .next_event()
            .await
            .map(|e| Arc::new(GossipEventWrapper { inner: e }))
    }

    /// Get the number of connected peers
    pub fn peer_count(&self) -> u32 {
        self.inner.peer_count()
    }

    /// Check if the node is online
    pub fn is_online(&self) -> bool {
        self.inner.is_online()
    }
}

/// Gossip event wrapper for UniFFI
#[derive(uniffi::Object)]
pub struct GossipEventWrapper {
    inner: gossip::GossipEvent,
}

#[uniffi::export]
impl GossipEventWrapper {
    /// Get the sender's peer ID
    pub fn sender(&self) -> String {
        self.inner.sender.clone()
    }

    /// Get the raw JSON content
    pub fn content(&self) -> String {
        self.inner.content.clone()
    }

    /// Get the timestamp (Unix seconds)
    pub fn timestamp(&self) -> i64 {
        self.inner.timestamp
    }

    /// Parse the content as a GossipMessage
    pub fn parse_message(&self) -> Option<Arc<GossipMessageWrapper>> {
        gossip::parse_gossip_message(&self.inner.content)
            .map(|m| Arc::new(GossipMessageWrapper { inner: m }))
    }
}

/// Gossip message wrapper for UniFFI
#[derive(uniffi::Object)]
pub struct GossipMessageWrapper {
    inner: gossip::GossipMessage,
}

#[uniffi::export]
impl GossipMessageWrapper {
    /// Get the message type as a string
    pub fn message_type(&self) -> String {
        match &self.inner {
            gossip::GossipMessage::Convoy { .. } => "convoy".to_string(),
            gossip::GossipMessage::Vote { .. } => "vote".to_string(),
            gossip::GossipMessage::DeleteConvoy { .. } => "delete_convoy".to_string(),
            gossip::GossipMessage::Channel { .. } => "channel".to_string(),
            gossip::GossipMessage::Blacklist { .. } => "blacklist".to_string(),
            gossip::GossipMessage::Trustlist { .. } => "trustlist".to_string(),
        }
    }

    /// Get the convoy data (only for "convoy" type messages)
    pub fn convoy_data(&self) -> Option<String> {
        match &self.inner {
            gossip::GossipMessage::Convoy { data } => Some(data.clone()),
            _ => None,
        }
    }

    /// Get the vote data (only for "vote" type messages)
    pub fn vote_data(&self) -> Option<String> {
        match &self.inner {
            gossip::GossipMessage::Vote { data } => Some(data.clone()),
            _ => None,
        }
    }

    /// Get the delete convoy info (only for "delete_convoy" type messages)
    pub fn delete_convoy_info(&self) -> Option<DeleteConvoyInfo> {
        match &self.inner {
            gossip::GossipMessage::DeleteConvoy {
                convoy_id,
                peer_id,
                signature,
            } => Some(DeleteConvoyInfo {
                convoy_id: convoy_id.clone(),
                peer_id: peer_id.clone(),
                signature: signature.clone(),
            }),
            _ => None,
        }
    }
}

/// Verify the ed25519 signature of a convoy event JSON
#[uniffi::export]
pub fn verify_convoy_signature(convoy_json: String) -> bool {
    gossip::verify_convoy_signature(&convoy_json)
}

/// Delete convoy info for UniFFI
#[derive(uniffi::Record)]
pub struct DeleteConvoyInfo {
    pub convoy_id: String,
    pub peer_id: String,
    pub signature: String,
}

/// Error type for P2P operations
#[derive(Debug, Clone, thiserror::Error, uniffi::Error)]
pub enum P2pError {
    #[error("Failed to initialize P2P node: {0}")]
    InitError(String),

    #[error("Failed to join topic: {0}")]
    JoinError(String),

    #[error("Node is not online")]
    NotOnline,

    #[error("Failed to shutdown node: {0}")]
    ShutdownError(String),

    #[error("Invalid data: {0}")]
    InvalidData(String),
}
