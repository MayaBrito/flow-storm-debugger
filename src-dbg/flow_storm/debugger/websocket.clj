(ns flow-storm.debugger.websocket
  (:require [flow-storm.utils :refer [log log-error]]
            [flow-storm.json-serializer :as serializer]
            [flow-storm.state-management :refer [defstate]]
            [flow-storm.debugger.config :refer [config]]
            [flow-storm.debugger.events-queue])
  (:import [org.java_websocket.server WebSocketServer]
           [org.java_websocket.handshake ClientHandshake]
           [org.java_websocket WebSocket]
           [org.java_websocket.exceptions WebsocketNotConnectedException]
           [org.java_websocket.framing CloseFrame]
           [java.net InetSocketAddress]
           [java.util UUID]))

(declare start-websocket-server)
(declare stop-websocket-server)
(declare websocket-server)

(defstate websocket-server
  :start (fn [_] (start-websocket-server))
  :stop  (fn [] (stop-websocket-server)))

(defn async-remote-api-request [method args callback]
  (let [conn @(:remote-connection websocket-server)]

    (if (nil? conn)
     (log-error "No process connected.")
     (try
       (let [request-id (str (UUID/randomUUID))
             packet-str (serializer/serialize [:api-request request-id method args])]
         (.send ^WebSocket conn ^String packet-str)
         (swap! (:pending-commands-callbacks websocket-server) assoc request-id callback))
       (catch WebsocketNotConnectedException _ nil)
       (catch Exception e
         (log-error "Error sending async command, maybe the connection is down, try to reconnect" e)
         nil)))))

(defn sync-remote-api-request
  ([method args] (sync-remote-api-request method args 10000))
  ([method args timeout]
   (let [p (promise)]

     (async-remote-api-request method args (fn [resp] (deliver p resp)))

     (let [v (deref p timeout :flow-storm/timeout)]
       (if (= v :flow-storm/timeout)
         (do
           (log-error "Timeout waiting for sync-remote-api-request response")
           nil)
         v)))))

(defn process-remote-api-response [[request-id err-msg resp :as packet]]
  (let [callback (get @(:pending-commands-callbacks websocket-server) request-id)]
    (if err-msg

      (do
       (log-error (format "Error on process-remote-api-response : %s" packet))
       ;; TODO: we should report errors to callers
       (callback nil))

     (callback resp))))


(defn- create-ws-server [{:keys [port on-message on-connection-open on-close]}]
  (let [ws-ready-promise (promise)
        server (proxy
                   [WebSocketServer]
                   [(InetSocketAddress. port)]

                 (onStart []
                   (log (format "WebSocket server started, listening on %s" port))
                   (deliver ws-ready-promise true))

                 (onOpen [^WebSocket conn ^ClientHandshake handshake-data]
                   (when on-connection-open
                     (on-connection-open conn))
                   (log (format "Got a connection %s" conn)))

                 (onMessage [conn message]
                   (on-message conn message))

                 (onClose [conn code reason remote?]
                   (log (format "Connection with debugger closed. conn=%s code=%s reson=%s remote?=%s"
                                conn code reason remote?))
                   (on-close code reason remote?))

                 (onError [conn ^Exception e]
                   (log-error "WebSocket error" e)))]

    [server ws-ready-promise]))

(defn stop-websocket-server []
  (when-let [wss (:ws-server websocket-server)]
    (.stop wss))
  (when-let [events-thread (:events-thread websocket-server)]
    (.interrupt events-thread))
  nil)

(defn start-websocket-server []
  (let [{:keys [dispatch-event on-connection-open]} config
        remote-connection (atom nil)
        events-callbacks (atom {:connection-going-away []
                                :connection-open []})
        [ws-server ready] (create-ws-server
                           {:port 7722
                            :on-connection-open (fn [conn]
                                                  (reset! remote-connection conn)
                                                  (when on-connection-open
                                                    (on-connection-open conn))
                                                  (doseq [cb (:connection-open @events-callbacks)] (cb)))
                            :on-message (fn [_ msg]
                                          (try
                                            (let [[msg-kind msg-body] (serializer/deserialize msg)]
                                              (case msg-kind
                                                :event (dispatch-event msg-body)
                                                :api-response (process-remote-api-response msg-body)))
                                            (catch Exception e
                                              (log-error (format "Error processing remote message '%s', error msg %s" msg (.getMessage e))))))

                            :on-close (fn [code _ _]
                                        (log-error (format "Connection closed with code %s" code))
                                        (cond

                                          (or (= code CloseFrame/GOING_AWAY)
                                              (= code CloseFrame/ABNORMAL_CLOSE))
                                          (doseq [cb (:connection-going-away @events-callbacks)] (cb))

                                          :else nil)

                                        )})]

    ;; see https://github.com/TooTallNate/Java-WebSocket/wiki/Enable-SO_REUSEADDR
    ;; if we don't have this we get Address already in use when starting twice in a row
    (.setReuseAddr ws-server true)
    (.start ws-server)

    ;; wait for the websocket to be ready before finishing this subsystem start
    ;; just to avoid weird race conditions
    @ready

    {:ws-server ws-server
     :pending-commands-callbacks (atom {})
     :events-callbacks events-callbacks
     :remote-connection remote-connection}))

(defn register-event-callback [event-key f]
  (swap! (:events-callbacks websocket-server) update event-key conj f))
