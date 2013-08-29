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
(ns pro.juxt.accounting.invoicing
  (:require
   [clojure.tools.logging :refer :all]
   [pro.juxt.accounting
    [database :as db]
    [util :refer (map-map)]]
   [clj-pdf.core :refer (pdf)]
   [datomic.api :as d]
   [clojure
    [edn :as edn]]
   [clojure.java.io :refer (file)]
   [clj-time
    [core :as time]
    [format :as timeformat]]
   [clojurewerkz.money.amounts :as ma :refer (total zero)])
  (:import
   (java.io File FileOutputStream)
   (java.util Date)
   (org.joda.money Money CurrencyUnit)
   (org.joda.time DateTime)))

(def date-formatter (timeformat/formatters :date))

(defn get-time [d] (.getTime d))

(defn until-pred [db d]
  (comp (partial > d) get-time :pro.juxt.accounting/date (partial d/entity db) :tx))

(defn get-common-entity [db items]
  (if (empty? items)
    (throw (ex-info "No items" {}))
    (let [entities
          (distinct (map (comp :pro.juxt.accounting/entity
                               (partial d/entity db)
                               :db/id
                               first
                               :pro.juxt.accounting/_debit
                               (partial d/entity db))
                         items))]
      (if (= 1 (count entities))
        (first entities)
        (throw (ex-info "No common entity, multiple entities involved" {:entities entities}))))))

(defn prepare-invoice [db invoice
                       & {entries :entries
                          debit-account :debit-to
                          output-tax-account :output-tax-account
                          output-tax-rate :output-tax-rate
                          invoice-date :invoice-date
                          issue-date :issue-date
                          invoice-ref-prefix :invoice-ref-prefix
                          first-invoice-ref :initial-invoice-suffix
                          purchase-order-reference :purchase-order-reference}]
  {:pre [(not (nil? output-tax-rate))]}
  ;; TODO: Must also come from a set of accounts with a single common currency - write a test first
  (let [entity (get-common-entity db (map :entry entries))]
    (when-not entity (throw (ex-info "All entries must belong to a single client to invoice." {:entries entries})))
    (vec
     (remove
      nil?
      (let [subtotal (total (map :amount entries))
            output-tax (-> subtotal (.multipliedBy
                                     (double output-tax-rate)
                                     java.math.RoundingMode/HALF_DOWN))
            tot (ma/plus subtotal output-tax)]

        (concat

         ;; Add statements for invoice-level data.
         [[:db/add invoice :pro.juxt.accounting/entity entity]
          [:pro.juxt.accounting/generate-invoice-ref invoice invoice-ref-prefix first-invoice-ref]
          [:db/add invoice :pro.juxt.accounting/subtotal (.getAmount subtotal)]
          [:db/add invoice :pro.juxt.accounting/currency (.getCode (.getCurrencyUnit subtotal))]
          [:db/add invoice :pro.juxt.accounting/output-tax (.getAmount output-tax)]
          [:db/add invoice :pro.juxt.accounting/total (.getAmount tot)]
          [:db/add invoice :pro.juxt.accounting/invoice-date (db/to-date invoice-date)]
          [:db/add invoice :pro.juxt.accounting/issue-date (db/to-date issue-date)]
          (when purchase-order-reference [:db/add invoice :pro.juxt.accounting/purchase-order-reference purchase-order-reference])
          ]

         ;; Add statements for each item in the invoice.
         ;; TODO Record the VAT applied
         (apply concat
                (for [{:keys [entry amount tx]} entries]
                  (let [id (d/tempid :db.part/user)
                        tx (d/entity db tx)]
                    [[:db/add invoice :pro.juxt.accounting/item id]
                     [:db/add id :pro.juxt.accounting/debit entry]
                     [:db/add entry :pro.juxt.accounting/invoice invoice]
                     [:db/add id :pro.juxt.accounting/date (:pro.juxt.accounting/date tx)]
                     [:db/add id :pro.juxt/description (:pro.juxt/description tx)]
                     [:db/add id :pro.juxt.accounting/amount (.getAmount amount)]
                     [:db/add id :pro.juxt.accounting/currency (.getCode (.getCurrencyUnit amount))]])))

         ;; Credit the accounts where the entries are drawn from because
         ;; they've now been invoiced.

         ;; Credit the VAT account, HMRC output-tax is incurred at the
         ;; time of invoice (unless cash-accounting basis).

         ;; Debit debit-account with total including VAT. This now needs
         ;; to be paid (within some time-frame).
         (let [txid (d/tempid :db.part/tx)]
           (concat
            (list
             [:db/add txid :pro.juxt/description (format "Invoicing %s (%s)" (:pro.juxt.accounting/name (db/to-entity-map entity db))
                                                         (.format (java.text.SimpleDateFormat. "d MMM y") (db/to-date invoice-date)))])
            (db/assemble-transaction
             db txid
             (db/to-date invoice-date)

             (for [[credit-account amount]
                   (-> (reduce-kv (fn [m k v]
                                    (assoc m k (total (map :amount entries))))
                                  {} (group-by :account entries))
                       (assoc output-tax-account output-tax))]
               {:amount amount :debit-account debit-account :credit-account credit-account}))))))))))


;; TODO This isn't really issuing the invoice because that's only when
;; it's been actually posted - rename accordingly
;; Perhaps this should be prepare-invoice, and prepare-invoice should be prepare-invoice-tx-data
(defn issue-invoice [conn & {account-to-credit :draw-from
                             account-to-debit :debit-to
                             output-tax-account :output-tax-account
                             output-tax-rate :output-tax-rate
                             invoice-date :invoice-date
                             issue-date :issue-date
                             invoice-ref-prefix :invoice-ref-prefix
                             first-invoice-ref :initial-invoice-suffix
                             purchase-order-reference :purchase-order-reference
                             :as options}]
  {:pre [(db/conn? conn)]}
  (let [db (d/db conn)
        entries-to-invoice
        (filter (every-pred
                 (until-pred db (.getTime invoice-date))
                 (comp not :invoice))
                (db/get-debits db account-to-credit))
        invoiceid (d/tempid :db.part/user)]

    (->> (prepare-invoice db invoiceid
                          :entries entries-to-invoice
                          :debit-to account-to-debit
                          :output-tax-account output-tax-account
                          :output-tax-rate output-tax-rate
                          :invoice-date invoice-date
                          :issue-date issue-date
                          :invoice-ref-prefix invoice-ref-prefix
                          :initial-invoice-suffix first-invoice-ref
                          :purchase-order-reference purchase-order-reference)
         (db/transact-insert conn invoiceid))))

;; This is not the invoice date!
#_(defn get-invoice-date [invoice db]
  (ffirst (d/q '[:find ?date
                 :in $ ?invoice
                 :where
                 [?invoice :pro.juxt.accounting/entity ?entity ?tx]
                 [?tx :db/txInstant ?date]
                 ] db (db/to-ref-id invoice))))

(defn get-invoice-items [invoice db]
  {:pre [(db/entity? invoice)
         (db/db? db)]}
  (->> (d/q '[:find ?date ?description ?amount ?currency
              :in $ ?invoice
              :where
              [?invoice :pro.juxt.accounting/item ?item]
              [?item :pro.juxt.accounting/date ?date]
              [?item :pro.juxt/description ?description]
              [?item :pro.juxt.accounting/amount ?amount]
              [?item :pro.juxt.accounting/currency ?currency]
              ] db (:db/id invoice))
       (sort-by (comp #(.getTime %) first) (comparator <))
       (map (partial zipmap [:date :description :amount :currency]))))

(defn printable-item [item]
  (map-map item
           {:date (comp #(timeformat/unparse date-formatter %)
                        #(DateTime. %)
                        #(.getTime %) :date)
            :description :description
            :amount (fn [item] (str (.getSymbol (CurrencyUnit/getInstance (:currency item)))
                                    (str (:amount item))))}))

(defn print-invoice [{:keys [items subtotal vat total invoice-date issue-date
                             invoice-ref invoice-addressee invoice-address
                             client-name issuer currency purchase-order-reference
                             output-tax-rate]} out]
  (let [currency-symbol (.getSymbol (CurrencyUnit/getInstance currency))]
    (pdf
     [{:size :a4 :pages true}
      [:table {:border-width 0}
       [[:cell {:align :left :border false :set-border [:bottom]}
         [:phrase {:size 20} "INVOICE"]]
        [:cell {:align :right :border false :set-border [:bottom]}
         [:chunk (apply str (conj (vec (interpose "\n" (cons (-> issuer :company-name) (-> issuer :company-address)))) "\n\n"))]]]

       [[:cell {:colspan 2 :border false} [:spacer 1]]]

       [[:cell {:align :left :border false}
         [:phrase {:style :bold}
          (as-> invoice-addressee &
                (when invoice-addressee (str "FAO: " &))
                (concat [& client-name] invoice-address)
                (interpose "\n" &)
                (conj (vec &) "\n\n")
                (apply str &))]]

        [:cell {:align :right :border false}
         [:table {:widths [70 30] :cell-border false :border-width 1}
          [[:cell {:align :right}
            [:chunk "Invoice ref:"]]
           [:cell {:align :right}
            [:chunk invoice-ref]]]
          (when purchase-order-reference
            [[:cell {:align :right}
              [:chunk "Purchase order:"]]
             [:cell {:align :right}
              [:chunk purchase-order-reference]]])
          [[:cell {:align :right}
            [:chunk "Invoice date:"]]
           [:cell {:align :right}
            [:chunk (timeformat/unparse date-formatter invoice-date)]]]
          [[:cell {:align :right}
            [:chunk "Issue date:"]]
           [:cell {:align :right}
            [:chunk (timeformat/unparse date-formatter issue-date)]]]
          [[:cell {:align :right}
            [:chunk "VAT number:"]]
           [:cell {:align :right}
            [:chunk (-> issuer :vat-no)]]]
          [[:cell {:align :right}
            [:chunk "Account:"]]
           [:cell {:align :right}
            [:chunk (-> issuer :bank-account-no)]]]
          [[:cell {:align :right}
            [:chunk "Sort-code:"]]
           [:cell {:align :right}
            [:chunk (-> issuer :bank-sort-code)]]]]]]]

      `[:table {:border-width 1 :cell-border true
                :widths [25 65 10]
                :header [{:color [255 255 255]} "Date" "Description" "Amount"]}

        ~@(for [{:keys [date description amount]} (map printable-item items)]
            [[:cell {:align :left} [:chunk date]]
             [:cell {:align :left} [:chunk description]]
             [:cell {:align :right} [:chunk amount]]])

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk "Subtotal"]]
         [:cell {:align :right} [:chunk ~(str currency-symbol subtotal)]]]

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk ~(str "VAT @ " (.format (java.text.DecimalFormat. "##") (* 100 output-tax-rate)) "%")]]
         [:cell {:align :right} [:chunk ~(str currency-symbol vat)]]]

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk "TOTAL"]]
         [:cell {:align :right} [:chunk ~(str currency-symbol total)]]]]

      [:spacer 2]
      [:chunk (-> issuer :notes)]
      [:spacer 4]
      [:line {:dotted true}]
      [:spacer 1]
      [:chunk (apply str (interpose "\n" ((juxt :signatory :title :company-name) issuer)))]]

     out)))

(defn create-invoice-data-template [issuer-fields]
  {:pre [(every? (set (keys issuer-fields)) [:title :signatory :company-name :company-address :vat-no :bank-account-no :bank-sort-code])]}
  (fn create-invoice-data [db invoice {:keys [output-dir output-tax-rate]}]
    ;;  {:pre [(db/entity? invoice)]}
    (let [entity (d/entity db (:pro.juxt.accounting/entity invoice))]
      (merge
       {:issuer issuer-fields
        :output-path (str output-dir java.io.File/separator
                          (:pro.juxt.accounting/invoice-ref invoice) ".pdf")
        :output-tax-rate output-tax-rate}
       (map-map entity
                {:client-name :pro.juxt.accounting/name
                 :invoice-addressee :pro.juxt.accounting/invoice-addressee
                 :invoice-address (comp edn/read-string :pro.juxt.accounting/invoice-address)})
       (map-map invoice
                {:invoice :db/id
                 :invoice-ref :pro.juxt.accounting/invoice-ref
                 :invoice-date (comp #(DateTime. %) :pro.juxt.accounting/invoice-date)
                 :issue-date (comp #(DateTime. %) :pro.juxt.accounting/issue-date)
                 :items (fn [invoice] (get-invoice-items invoice db))
                 :currency :pro.juxt.accounting/currency
                 :subtotal :pro.juxt.accounting/subtotal
                 :vat :pro.juxt.accounting/output-tax
                 :total :pro.juxt.accounting/total
                 :purchase-order-reference :pro.juxt.accounting/purchase-order-reference
                 })))))

(defn generate-pdf-for-invoice [conn {:keys [invoice invoice-ref ^File output-path] :as invoice-data}]
  {:pre [(not (nil? invoice-ref))
         (not (nil? output-path))]}
  (let [f (file output-path)]
    (info "Printing PDF invoice to " f)
    (print-invoice invoice-data (FileOutputStream. f))
    @(d/transact conn [[:db/add invoice :pro.juxt.accounting/pdf-file output-path]])
    #_(if (.exists f)
        (info "PDF invoice already exists, so will not overwrite: " f)
        (do
          (info "Printing PDF invoice to " f)
          (print-invoice invoice-data (FileOutputStream. f))))))
