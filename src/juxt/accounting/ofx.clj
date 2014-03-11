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
(ns juxt.accounting.ofx
  (:require
   jig
   [clojure.xml :as xml]
   [instaparse.core :as insta]
   [datomic.api :as d]
   [juxt.accounting.pretty :refer (ppxml)]
   [juxt.accounting.database :as db]
   [clojure.zip :as zip :refer (xml-zip)]
   [clojure.data.zip.xml :refer (xml-> xml1-> tag= text)]
   [camel-snake-kebab :refer (->kebab-case-keyword)]
   [hiccup.core :as hiccup]
   [juxt.datomic.extras :refer (as-conn as-db)]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)]
   [juxt.accounting.money :refer (as-money)]
   [clojure.set :refer (union)]
   [clj-time.format :as timeformat]
   [clojure.java.io :as io]
   [clojure.tools.logging :refer :all])
  (:import (jig Lifecycle))
  )

(defn parser []
  (-> "juxt/accounting/ofx.bnf" io/resource slurp (insta/parser :output-format :enlive)))

(defn- header [ast]
  (assert (not (insta/failure? ast)))
  (assert (= 1 (count ast)))
  (let [loc (xml-zip (first ast))]
    (into {}
          (for [tuple (xml-> loc :Header :MetadataTuple)]
            [(->kebab-case-keyword (xml1-> tuple :MetadataName text))
             (xml1-> tuple :MetadataValue text)]))))

(defn- transform [ast]
  (->> ast
       (insta/transform
        {:LeafElement
         (fn [& args]
           (let [loc (xml-zip {:tag :Root :attrs {} :content args})]
             {:tag (->kebab-case-keyword (xml1-> loc :OpenTag :TagName text))
              :attrs {}
              :content (vector (xml1-> loc :Value text))}
             ))
         :NodeElement
         (fn [& args]
           (let [loc (xml-zip {:tag :Root :attrs {} :content args})]
             {:tag (->kebab-case-keyword (xml1-> loc :OpenTag :TagName text))
              :attrs {}
              :content (xml-> loc :Element zip/node)}))})
       (insta/transform {:Element (fn [& args] (first args))})
       first))

(defn parse [f]
  (let [doc (insta/parses (parser) (slurp f))]
    {:header (header doc)
     :doc (xml1-> (xml-zip (transform doc)) :RootElement :ofx zip/node)}))

(defn extract-transactions [f]
  (let [{:keys [doc]} (parse f)]
    (into {}
          (for [acct (xml-> (xml-zip doc) :bankmsgsrsv-1 :stmttrnrs :stmtrs)]
            [{:accno (xml1-> acct :bankacctfrom :acctid text)
              :sort-code (xml1-> acct :bankacctfrom :bankid text)}
             (set
              (for [tx (xml-> acct :banktranlist :stmttrn)]
                {:type (case (xml1-> tx :trntype text)
                         "CREDIT" :debit
                         ("DEBIT" "DIRECTDEBIT") :credit
                         (throw (ex-info (format "Unrecognized OFX transaction type: %s"
                                                 (xml1-> tx :trntype text)) {}) ))
                 :amount (xml1-> tx :trnamt text)
                 :currency (xml1-> acct :curdef text)
                 :date (xml1-> tx :dtposted text)
                 :name (xml1-> tx :name text)
                 :fitid (xml1-> tx :fitid text)
                 :memo (xml1-> tx :memo text)}))]))))



(defn add-transactions [dir dburi account-mappings]
  {:pre [dburi account-mappings]}

  (let [dir (io/file dir)
        conn (as-conn dburi)
        db (d/db conn)]

    #_(println "OFX: current accounts in scope")

    #_(clojure.pprint/pprint (juxt.accounting.database/get-accounts db))

    (doseq [[acct transactions]
            (->> dir (.listFiles)
                 (filter (comp not (partial re-matches #".*\.md") (memfn getName)))
                 (map extract-transactions)
                 (apply merge-with union))]
      (if-let [acct (:ident (db/find-account db :account-no (:accno acct) :sort-code (:sort-code acct)))]
        (doseq [tx transactions]
          (debugf "tx is %s" tx)
          (let [credit-account
                (case (:type tx)
                  :debit (some->> (get-in account-mappings [(:name tx) :credit])
                                  (db/find-account db :ident)
                                  :ident)
                  :credit acct)
                debit-account
                (case (:type tx)
                  :debit acct
                  :credit (some->> (get-in account-mappings [(:name tx) :debit])
                                   (db/find-account db :ident)
                                   :ident))]
            (if (and debit-account credit-account)
              (do
                (debugf "Credit account %s" credit-account)
                (debugf "Debit account %s" debit-account)
                @(d/transact
                  (as-conn dburi)
                  (db/assemble-transaction
                   db (db/to-date (timeformat/parse (timeformat/formatter "yyyyMMdd") (:date tx)))
                   [{:debit-account debit-account
                     :credit-account credit-account
                     :amount (as-money (Math/abs (Double/parseDouble (:amount tx))) (:currency tx))
                     :description (format "%s (ref: %s)" (:name tx) (:memo tx))}]
                   "Loaded from NatWest statement")))
              (throw (ex-info (format "Cannot recognise %s transaction name in statement, name is %s, memo is %s" (:type tx) (:name tx) (:memo tx)) (assoc tx :credit-account credit-account :debit-account debit-account :account-mappings account-mappings))))))
        (throw (ex-info (format "Failed to find account, accno = %s, sort code = %s" (:accno acct) (:sort-code acct))))))))

;; An optional module that can process statements.
;; Depends on juxt.accounting.components.StaticLoader
(deftype StatementProcessor [config]
  Lifecycle
  (init [_ system]
    system)
  (start [_ system]
    (let [dir (:statement-directory config)]
      (let [dburi (some-> system :jig/config :jig/components
                          (get (:database config)) :db :uri)]
        (add-transactions (io/file dir) dburi (:account-mappings config)))
      system))
  (stop [_ system] system)
)
