;; Copyright © 2013, JUXT Ltd. All Rights Reserved.
;;
;; This file is part of JUXT Accounting.
;;
;; JUXT Accounting is free software: you can redistribute it and/or modify it under the
;; terms of the GNU Affero General Public License as published by the Free
;; Software Foundation, either version 3 of the License, or (at your option) any
;; later version.
;;
;; JUXT Accounting is distributed in the hope that it will be useful but WITHOUT ANY
;; WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
;; A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more
;; details.
;;
;; Please see the LICENSE file for a copy of the GNU Affero General Public License.
;;
(ns pro.juxt.accounting.repl
  (require
   [clojure.pprint :refer (pprint)]
   [taoensso.timbre :as timbre]
   [pro.juxt.accounting
    [database :as db]
    [config :refer (config)]]))

(defn ^::command init-database "Create and initialise the database." []
  (db/init (:dburi (config)))
  :ok)

(defn ^{::command true
        :doc "Set log settings. (logging :level :debug) will set the logging level to debug. With no arguments it will print the logging configuration."}
  logging
  ([& {:keys [level]}]
     (when level
       (timbre/set-level! level)
       (timbre/info "Info logging on")
       (timbre/debug "Debug logging on")
       (timbre/trace "Trace logging on"))
     :ok)
  ([] (pprint @timbre/config) :ok))

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
    (let [banner "JUXT Accounting. Copyright © 2013, JUXT Ltd. All Rights Reserved."]
      (println banner)
      (println (apply str (repeat (count banner) \-))))
    (println "Welcome to JUXT Accounting")
    (println "Type '(help)' for commands and '(exit)' to quit.")
    (init-database)))
