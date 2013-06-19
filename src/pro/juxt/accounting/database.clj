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
(ns pro.juxt.accounting.database
  "Database access functions."
  (:require
   [clojure
    [set :as set]
    [edn :as edn]]
   [clojure.java [io :refer (reader)]]
   [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy)]
   [datomic.api :refer (q db transact transact-async) :as d]))

(def ^:dynamic *dburi* "The Datomic database uri, Set by nrepl handlers, don't assume in functions." nil)

(def functions {})

(defn create-functions [functions]
  (vec
   (for [[ident {:keys [doc params path]}] functions]
     {:db/id (d/tempid :db.part/user)
      :db/ident ident
      :db/doc doc
      :db/fn (d/function {:lang "clojure" :params params :code (slurp path)})})))

(defn init [dburi]
  (if (d/create-database dburi)
    (debug "Created database" dburi)
    (debug "Using existing database" dburi))
  (doto (d/connect dburi)
    (d/transact (read-string (slurp "resources/schema.edn")))
    (d/transact (create-functions functions))))

(defmacro insert
  "Blocking update. Returns the id corresponding to the given symbol."
  [conn temp stmts]
  `(let [~temp (d/tempid :db.part/user)
         {:keys [~'db-after ~'tempids]}
         @(transact ~conn (vec ~stmts))]
     (d/resolve-tempid ~'db-after ~'tempids ~temp)))

(defn create-account!
  "Create an account and return its id."
  [conn name]
  (insert conn account [[:db/add account :pro.juxt/name name]]))

(defn get-name
  "Get the unique name of the given entity."
  [db e]
  (:pro.juxt/name (d/entity db e)))
