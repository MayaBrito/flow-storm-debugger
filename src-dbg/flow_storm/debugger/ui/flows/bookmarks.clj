(ns flow-storm.debugger.ui.flows.bookmarks
  (:require [flow-storm.debugger.state :as dbg-state :refer [store-obj obj-lookup]]
            [flow-storm.debugger.ui.utils
             :as ui-utils
             :refer [icon-button table-view label v-box]]
            [clojure.string :as str]
            [flow-storm.utils :refer [log-error]])
  (:import [javafx.scene Scene]
           [javafx.stage Stage]
           [javafx.scene.input MouseButton]))

(defn update-bookmarks [flow-id thread-id]
  (when-let [[{:keys [clear add-all]}] (obj-lookup flow-id thread-id "bookmarks_table_data")]
    (let [bookmarks (dbg-state/all-bookmarks flow-id thread-id)]
      (clear)
      (add-all (mapv (fn [[b-idx b-text]]
                       (let [bookmark {:idx b-idx
                                       :text b-text}]
                         [(assoc bookmark :cell-type :text)
                          (assoc bookmark :cell-type :actions)]))
                     bookmarks)))))

(defn bookmark-add [flow-id thread-id idx]
  (let [text (ui-utils/ask-text-dialog {:header "Add bookmark"
                                        :body "Bookmark name:"})]
    (dbg-state/add-bookmark flow-id thread-id idx text)
    (update-bookmarks flow-id thread-id)))

(defn- create-bookmarks-pane [flow-id thread-id]
  (let [cell-factory (fn [_ {:keys [cell-type idx text]}]
                       (case cell-type
                         :text (label text)
                         :actions (icon-button :icon-name "mdi-delete-forever"
                                               :on-click (fn []
                                                           (dbg-state/remove-bookmark flow-id thread-id idx)
                                                           (update-bookmarks flow-id thread-id)))))
        {:keys [table-view-pane] :as tv-data} (table-view
                                               {:columns             ["Bookmarks" ""]
                                                :columns-width-percs [0.9         0.1]
                                                :cell-factory-fn cell-factory
                                                :resize-policy :constrained
                                                :on-click (fn [mev sel-items _]
                                                            (when (and (= MouseButton/PRIMARY (.getButton mev))
                                                                       (= 2 (.getClickCount mev)))
                                                              (let [idx (-> sel-items first first :idx)
                                                                    goto-loc (requiring-resolve 'flow-storm.debugger.ui.flows.screen/goto-location)]
                                                                (goto-loc {:flow-id   flow-id
                                                                           :thread-id thread-id
                                                                           :idx       idx}))))
                                                :selection-mode :multiple
                                                :search-predicate (fn [[_ bookmark-text] search-str]
                                                                    (str/includes? bookmark-text search-str))})]
    (store-obj flow-id thread-id "bookmarks_table_data" tv-data)
    (doto (v-box [(label (format "Bookmarks for thread: %s" (:thread/name (dbg-state/get-thread-info thread-id))))
                  table-view-pane])
      (.setSpacing 10))))

(defn show-bookmarks [flow-id thread-id]
  (try
    (let [scene (Scene. (create-bookmarks-pane flow-id thread-id) 800 400)
          stage (doto (Stage.)
                  (.setTitle "FlowStorm bookmarks")
                  (.setScene scene))]

      (dbg-state/register-and-init-stage! stage)

      (-> stage .show))

    (update-bookmarks flow-id thread-id)

    (catch Exception e
      (log-error "UI Thread exception" e))))
