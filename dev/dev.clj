;; Copyright © 2013, JUXT LTD. All Rights Reserved.
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
(ns dev
  (:require
   [io.pedestal.service.http :as bootstrap]
   [datomic.api :as d]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :refer (pprint)]
   [clojure.repl :refer :all]
   [clojure.tools.namespace.repl :refer (refresh refresh-all)]
   [pro.juxt.accounting
    [database :as db]
    [service :as service]
    [system :as system]
    [driver :refer (process-accounts-file)]]))

(defn create-service [system]
  (-> (service/create-service system) ;; start with production configuration
      (merge  {:env :dev
               ;; do not block thread that starts web server
               ::bootstrap/join? false
               ;; reload routes on every request
               ::bootstrap/routes #(service/create-routes system)
               ;; all origins are allowed in dev mode
               ::bootstrap/allowed-origins (constantly true)})
      (bootstrap/default-interceptors)
      (bootstrap/dev-interceptors)))

(def system nil)

(defn init
  "Constructs the current development system."

  []
  (letfn [(try-config-file [fname] (let [f (io/file fname)]
                                     (when (and (.exists f) (.isFile f)) f)))]
    (alter-var-root #'system
                    (constantly
                     (system/system
                      (edn/read-string
                       (slurp (or (try-config-file "config.edn")
                                  (try-config-file (io/file (System/getProperty "user.home") ".juxt-accounts/config.edn"))))))))))


(defn start
  "Starts the current development system."
  []
  (alter-var-root #'system system/start create-service))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'system
    (fn [s] (when s (system/stop s)))))

(defn go
  "Initializes the current development system and starts it running."
  []
  (init)
  (start))

(defn reset []
  (stop)
  (refresh :after 'dev/go))
