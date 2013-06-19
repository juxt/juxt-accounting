;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by the
;; terms of this license.
;;
;; You must not remove this notice, or any other, from this software.
(ns pro.juxt.accounting.repl
  (require
   [clojure.pprint :refer (pprint)]
   [taoensso.timbre :as timbre]
   [pro.juxt.accounting
    [config :refer (config)]
    [database :as db :refer (*dburi*)]]))

(defn ^::command init-database "Create and initialise the database." []
  (db/init (:dburi (config)))
  :ok)

(defn ^::command logging "Set log settings. (logging :level :debug) will set the logging level to debug. With no arguments it will print the logging configuration."
  ([& {:keys [level]}]
     (when level
       (timbre/set-level! level)
       (timbre/info "Info logging on")
       (timbre/debug "Debug logging on")
       (timbre/trace "Trace logging on"))
     :ok)
  ([] (pprint @timbre/config) :ok)
  )

(defn ^::command help "List available commands." []
  (doseq [[k v] (ns-publics *ns*)]
    (let [m (meta v)]
      (when (::command m)
        (println (format "(%s) %s%s"
                         (:name m)
                         (apply str (repeat (- 20 (count (str (:name m)))) \ ))
                         (:doc m))))))

  :ok)

(defn init []
  (do
    (timbre/set-level! :info)
    (println "JUXT Accounting. Copyright © 2013, JUXT Ltd. All Rights Reserved.")
    (println (apply str (repeat 60 \-)))
    (println "Welcome to JUXT Accounting")
    (println "Type '(help)' for commands and '(exit)' to quit.")
    (init-database)))

(defn bind-dburi [handler]
  (fn [msg]
    (binding [*dburi* (:dburi (config))]
      (handler msg))))
