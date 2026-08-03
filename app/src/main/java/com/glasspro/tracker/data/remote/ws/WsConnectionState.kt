package com.glasspro.tracker.data.remote.ws

/**
 * Lifecycle of a single exchange WebSocket connection. Every adapter owns one
 * or more connections and reports their state so the UI can render a live
 * health dashboard (which exchanges are LIVE / STALE / DOWN right now).
 */
enum class WsConnectionState {
    DISCONNECTED,
    CONNECTING,
    SUBSCRIBING,
    LIVE,
    STALE,
    BACKOFF
}
