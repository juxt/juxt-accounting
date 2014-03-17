(ns juxt.accounting.loaders.team-project
  (:refer-clojure :exclude [read *data-readers* read-string])
  (:require
   [datomic.api :as d]
   [juxt.accounting.loaders :refer (issue-invoices)]
   [clojure.java.io :as io]
   [clojure.tools.reader :refer (read *data-readers* read-string)]
   [clojure.tools.reader.reader-types :refer (indexing-push-back-reader source-logging-push-back-reader)]
   [clojurewerkz.money.currencies :as mc :refer (GBP to-currency-unit)]
   [clojurewerkz.money.amounts :as ma]
   [juxt.accounting.database :as db]
   [juxt.accounting.money :refer (as-money)]
   [juxt.accounting.time :refer (to-local-date)]
   [clojure.pprint :refer (pprint)]
   [clj-time
    [core :as time]
    [coerce :refer (from-date to-date)]
    [periodic :refer (periodic-seq)]
    [format :as timeformat]]
   )
  (:import (jig Lifecycle))
  )

(defmulti get-billings (fn [associate date-stop] (:basis associate)))

(defn weekend? [ld] (>= (time/day-of-week ld) 6))

(defn min-local-date [coll]
  (->> coll (map to-local-date) (remove nil?) sort first))

;; date-stop and end-date in period is treated as inclusive
(defmethod get-billings :regular [{:keys [dir periods debit-account credit-account name]} date-stop]
  (mapcat
   (fn [{:keys [start-date end-date rate cost]}]
     (let [end-date-exclusive (time/plus (min-local-date [end-date date-stop]) (time/days 1))]
       (for [day
             (->> (periodic-seq (to-local-date start-date) (time/days 1))
                  (take-while #(time/before? % end-date-exclusive))
                  (remove weekend?))]
         {:date day
          :debit-account debit-account
          :credit-account credit-account
          :name name
          :amount (as-money rate GBP)
          :cost (as-money cost GBP)})))
   periods))

(defmethod get-billings :irregular [{:keys [dir periods]} date-stop]
  [])

;; TODO Remove holidays

(defn add-associate-transactions [dburi txfile m]
  (let [conn (d/connect dburi)
        db (d/db conn)]
    (doseq [{:keys [date debit-account credit-account description amount cost name]}
            (mapcat get-billings (:associates m) (repeat (time/local-date 2014 9 15)))]
      @(d/transact
        conn
        (db/assemble-transaction
         db
         (to-date date)
         [{:debit-account debit-account
           :credit-account :juxt.pnl
           :description (format "Full day by %s" name)
           :amount amount
           }
          {:debit-account :juxt.pnl
           :credit-account credit-account
           :description (format "Full day by %s" name)
           :amount cost
           }
          ]
         "Test transaction")))))

(deftype TeamProjectLoader [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (assert (:dburi system))
    (assert (:transaction-file config) "No transaction file")
    (let [txfile (io/file (:transaction-file config))
          _ (assert (.exists txfile) (format "Transaction file doesn't exist: %s" txfile))
          _ (assert (.isFile txfile) (format "Transaction exists but is not a file: %s" txfile))

          txfile-content
          (binding [*data-readers* {'juxt.accounting/currency (fn [x] (to-currency-unit (str x)))}]
            (read
             (indexing-push-back-reader
              (java.io.PushbackReader. (io/reader txfile))
              )))]

      (add-associate-transactions
       (:dburi system)
       txfile
       txfile-content)

      (issue-invoices (:dburi system) (:invoices txfile-content))

      (assoc system ::associates (:associates txfile-content))))

  (stop [_ system] system))
