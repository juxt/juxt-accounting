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
(ns pro.juxt.accounting.invoicing
  (:require
   [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy)]
   [pro.juxt.accounting
    [database :as db]
    [util :refer (map-map)]]
   [clj-pdf.core :refer (pdf)]
   [datomic.api :as d]
   [clojure
    [edn :as edn]]
   [clj-time
    [core :as time]
    [format :as timeformat]]
   [clojurewerkz.money.amounts :as ma :refer (total zero)])
  (:import
   (java.util Date)
   (org.joda.money Money CurrencyUnit)
   (org.joda.time DateTime)))

(def date-formatter (timeformat/formatters :date))

(defn get-time [d] (.getTime d))

(defn until-pred [db d]
  (comp (partial > d) get-time :pro.juxt.accounting/date (partial d/entity db) :tx))

(defn get-common-client [db items]
  (let [clients
        (distinct (map (comp :pro.juxt.accounting/parent
                             (partial d/entity db)
                             :db/id
                             first
                             :pro.juxt.accounting/_debit
                             (partial d/entity db))
                       items))]
    (if (= 1 (count clients))
      (first clients)
      (throw (ex-info "No common owner to, multiple clients involved" {:clients clients})))))

(defn prepare-invoice [db invoice entries debit-account
                       vat-account invoice-ref-prefix first-invoice-ref]
  ;; TODO: Must also come from a set of accounts with a single common currency - write a test first
  (let [client (get-common-client db (map :entry entries))]
    (when-not client (throw (ex-info "All entries must belong to a single client to invoice." {:entries entries})))
    (vec
     (let [subtotal (total (map :amount entries))
           vat (-> subtotal (ma/multiply 20) (ma/divide 100))
           tot (ma/plus subtotal vat)]

       (concat

        ;; Add statements for invoice-level data.
        [[:db/add invoice :pro.juxt.accounting/parent client]
         [:pro.juxt.accounting/generate-invoice-ref invoice invoice-ref-prefix first-invoice-ref]
         [:db/add invoice :pro.juxt.accounting/subtotal (.getAmount subtotal)]
         [:db/add invoice :pro.juxt.accounting/currency (.getCode (.getCurrencyUnit subtotal))]
         [:db/add invoice :pro.juxt.accounting/vat (.getAmount vat)]
         [:db/add invoice :pro.juxt.accounting/total (.getAmount tot)]]

        ;; Add statements for each item in the invoice.
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

        ;; Credit the accounts where the entries are drawn from because they've now been invoiced.
        ;; Credit the VAT account, HMRC output-tax is incurred at the time of invoice (usually).
        ;; Debit debit-account with total including VAT. This now needs to be paid (within some time-frame).
        (let [txid (d/tempid :db.part/tx)]
          (db/assemble-transaction
           db txid
           :date (Date.)
           :debits {debit-account tot}
           :credits (-> (reduce-kv (fn [m k v]
                            (assoc m k (total (map :amount entries))))
                                   {} (group-by :account entries))
                        (assoc vat-account vat)))))))))

(defn issue-invoice [conn account-to-credit account-to-debit vat-account until invoice-ref-prefix first-invoice-ref]
  {:pre [(db/conn? conn)]}
  (let [db (d/db conn)
        entries-to-invoice
        (filter (every-pred
                 (until-pred db until)
                 (comp not :invoice))
                (db/get-debits db account-to-credit))
        invoiceid (d/tempid :db.part/user)]

    (->> (prepare-invoice db invoiceid
                          entries-to-invoice
                          account-to-debit
                          vat-account
                          invoice-ref-prefix first-invoice-ref)
         (db/transact-insert conn invoiceid))))

(defn get-invoice-date [invoice db]
  (ffirst (d/q '[:find ?date
                 :in $ ?invoice
                 :where
                 [?invoice :pro.juxt.accounting/parent ?client ?tx]
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

(defn print-invoice [{:keys [items subtotal vat total invoice-date
                             invoice-ref client-address
                             client-addressee client-name
                             issuer currency-symbol]} out]
  (pdf
   [{:size :a4 :pages true}
    [:table {:border-width 0}
     [[:cell {:align :left :border false :set-border [:bottom]}
       [:phrase {:size 20} "INVOICE"]]
      [:cell {:align :right :border false :set-border [:bottom]}
       [:chunk (apply str (conj (vec (interpose "\n" (-> issuer :company-address))) "\n\n"))]]]

     [[:cell {:colspan 2 :border false} [:spacer 1]]]

     [[:cell {:align :left :border false}
       [:phrase {:style :bold}
        (as-> client-addressee &
              (str "FAO " &)
              (concat [& client-name] client-address)
              (interpose "\n" &)
              (conj (vec &) "\n\n")
              (apply str &))]]

      [:cell {:align :right :border false}
       [:table {:widths [70 30] :cell-border false :border-width 1}
        [[:cell {:align :right}
          [:chunk "Invoice ref:"]]
         [:cell {:align :right}
          [:chunk invoice-ref]]]
        [[:cell {:align :right}
          [:chunk "Invoice date:"]]
         [:cell {:align :right}
          [:chunk invoice-date]]]
        [[:cell {:align :right}
          [:chunk "VAT:"]]
         [:cell {:align :right}
          [:chunk (-> issuer :vat-no)]]]
        [[:cell {:align :right}
          [:chunk "Account:"]]
         [:cell {:align :right}
          [:chunk (-> issuer :bank-account-no)]]]
        [[:cell {:align :right}
          [:chunk "Sortcode:"]]
         [:cell {:align :right}
          [:chunk (-> issuer :bank-sort-code)]]]]]]]

    `[:table {:border-width 1 :cell-border true
              :widths [25 65 10]
              :header [{:color [255 255 255]} "Date" "Description" "Amount"]}

      ~@(for [{:keys [date description amount]} items]
          [[:cell {:align :left} [:chunk date]]
           [:cell {:align :left} [:chunk description]]
           [:cell {:align :right} [:chunk amount]]])

      [[:cell {:align :left} [:chunk ""]]
       [:cell {:align :left} [:chunk "Subtotal"]]
       [:cell {:align :right} [:chunk ~(str currency-symbol subtotal)]]]

      [[:cell {:align :left} [:chunk ""]]
       [:cell {:align :left} [:chunk "VAT"]]
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

   out))

(defn printable-item [item]
  (map-map item
           {:date (comp #(timeformat/unparse date-formatter %)
                        #(DateTime. %)
                        #(.getTime %) :date)
            :description :description
            :amount (fn [item] (str (.getSymbol (CurrencyUnit/getInstance (:currency item)))
                                    (str (:amount item))))}))

(defn create-invoice-pdf-template [issuer-fields]
  {:pre [(every? (set (keys issuer-fields)) [:title :signatory :company-name :company-address :vat-no :bank-account-no :bank-sort-code])]}
  (fn [invoice db]
    ;;  {:pre [(db/entity? invoice)]}

    (let [client (d/entity db (:pro.juxt.accounting/parent invoice))
          fields (merge
                  {:issuer issuer-fields}
                  (map-map client
                           {:client-addressee (comp first :pro.juxt.accounting/principal)
                            :client-name :pro.juxt.accounting/name
                            :client-address (comp edn/read-string :pro.juxt.accounting/postal-address)})

                  (map-map invoice
                           {:invoice-ref :pro.juxt.accounting/invoice-ref
                            :invoice-date (comp #(timeformat/unparse date-formatter %)
                                                #(DateTime. %)
                                                #(get-invoice-date % db))
                            :items (fn [invoice] (->>
                                                  (get-invoice-items invoice db)
                                                  (map printable-item)))
                            :currency-symbol (comp #(.getSymbol %)
                                                   #(CurrencyUnit/getInstance %)
                                                   :pro.juxt.accounting/currency)
                            :subtotal :pro.juxt.accounting/subtotal
                            :vat :pro.juxt.accounting/vat
                            :total :pro.juxt.accounting/total}))]

      (info "Printing PDF invoice to "(str (:invoice-ref fields) ".pdf"))
      (print-invoice
       fields
       (str (:invoice-ref fields) ".pdf")))))
