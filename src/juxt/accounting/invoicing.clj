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
(ns juxt.accounting.invoicing
  (:require
   [clojure.tools.logging :refer :all]
   [clojure.java.io :as io]
   [juxt.accounting
    [database :as db]
    [money :refer (as-currency)]]
   [clj-pdf.core :refer (pdf)]
   [datomic.api :as d]
   [juxt.datomic.extras :refer (to-entity-map db? as-db)]
   [juxt.datomic.extras.spider :refer (spider)]
   [clojure
    [edn :as edn]]
   [clojure.java.io :refer (file)]
   [clj-time
    [core :as time]
    [coerce :refer (from-date)]
    [format :as timeformat]]
   [clojurewerkz.money.amounts :as ma :refer (total zero multiply)])
  )

(def date-formatter (timeformat/formatters :date))

(defn get-time [d] (.getTime d))

(defn until-pred [d]
  (comp (partial > d) get-time :date))

(defn prepare-invoice [db invoice
                       & {entity :entity
                          components :components
                          debit-account :debit-to
                          vat-account :vat-account
                          vat-rate :vat-rate
                          invoice-date :invoice-date
                          issue-date :issue-date
                          invoice-ref-prefix :invoice-ref-prefix
                          first-invoice-ref :initial-invoice-suffix
                          purchase-order-reference :purchase-order-reference}]
  {:pre [vat-rate vat-account]}

  ;; TODO: Must also come from a set of accounts with a single common currency - write a test first

  ;; Ensure vat-account is real
  (assert (:db/id (to-entity-map vat-account db)) (str "No such account: " vat-account))

  (assert (pos? (count components)) (format "No items for invoice %s issued on date %s" invoice-ref-prefix issue-date))


  (vec
   (remove
    nil?
    (let [subtotal (total (map :value components))
          ;; TODO Try with multiply now money bug fixed
          vat (multiply subtotal
                        (double vat-rate)
                        java.math.RoundingMode/HALF_DOWN)
          tot (ma/plus subtotal vat)
          txid (d/tempid :db.part/tx)]

      (concat

       ;; Add statements for invoice-level data.
       [[:db/add invoice :juxt.accounting/entity entity]
        [:juxt.accounting/generate-invoice-ref invoice invoice-ref-prefix first-invoice-ref]
        [:db/add invoice :juxt.accounting/subtotal (.getAmount subtotal)]
        [:db/add invoice :juxt.accounting/currency (.getCode (.getCurrencyUnit subtotal))]
        [:db/add invoice :juxt.accounting/vat (.getAmount vat)]
        [:db/add invoice :juxt.accounting/total (.getAmount tot)]
        [:db/add invoice :juxt.accounting/invoice-date (db/to-date invoice-date)]
        [:db/add invoice :juxt.accounting/issue-date (db/to-date issue-date)]
        (when purchase-order-reference [:db/add invoice :juxt.accounting/purchase-order-reference purchase-order-reference])
        [:db/add txid :juxt/description (format "Invoice to %s (%s)" (:juxt.accounting/name (to-entity-map entity db)) (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (db/to-date issue-date)))]
        ]

       ;; Add statements for each item in the invoice.
       ;; TODO Record the VAT applied

       (apply concat
              (for [{date :date component-id :id :as component} components]
                (let [id (d/tempid :db.part/user)]
                  [[:db/add invoice :juxt.accounting/item id]
                   [:db/add id :juxt.accounting/invoice-item-component component-id]
                   ])))

       ;; Credit the accounts where the entries are drawn from because
       ;; they've now been invoiced.

       ;; Credit the VAT account, HMRC VAT is incurred at the
       ;; time of invoice (unless cash-accounting basis).

       ;; Debit debit-account with total including VAT. This now needs
       ;; to be paid (within some time-frame).
       (let [description (format "Invoicing %s (%s)" (:juxt.accounting/name (to-entity-map entity db))
                                   (.format (java.text.SimpleDateFormat. "d MMM y") (db/to-date invoice-date)))]
         (db/assemble-transaction
          db
          (db/to-date invoice-date)
          (concat
           (for [[credit-account amount] (reduce-kv (fn [m k v]
                                                      (assoc m k (total (map :value v))))
                                                    {} (group-by (comp :db/id :account) components))]
             {:amount amount :debit-account debit-account :credit-account credit-account :description description :component-type :net})
           [{:amount vat :debit-account debit-account :credit-account vat-account :description (str "VAT on " description) :component-type :vat}])
          "invoicing: Invoice credits")))))))

;; TODO This isn't really issuing the invoice because that's only when
;; it's been actually posted - rename accordingly
;; Perhaps this should be prepare-invoice, and prepare-invoice should be prepare-invoice-tx-data
(defn issue-invoice [conn & {account-to-credit :draw-from
                             account-to-debit :debit-to
                             vat-account :vat-account
                             vat-rate :vat-rate
                             invoice-date :invoice-date
                             issue-date :issue-date
                             invoice-ref-prefix :invoice-ref-prefix
                             first-invoice-ref :initial-invoice-suffix
                             purchase-order-reference :purchase-order-reference
                             :as options}]
  {:pre [(db/conn? conn)]}
  (let [db (d/db conn)
        entity (:juxt.accounting/entity (to-entity-map account-to-credit db))
        components-to-invoice
        (filter (every-pred
                 (until-pred (.getTime invoice-date))
                 ;; Here's the bit - :juxt.accounting/invoice-item-component
                 (comp not :invoice-item))
                (db/get-account-components db account-to-credit :juxt.accounting/debit))
        invoiceid (d/tempid :db.part/user)]

    (->> (prepare-invoice db invoiceid
                          :entity entity
                          :components components-to-invoice
                          :debit-to account-to-debit
                          :vat-account vat-account
                          :vat-rate vat-rate
                          :invoice-date invoice-date
                          :issue-date issue-date
                          :invoice-ref-prefix invoice-ref-prefix
                          :initial-invoice-suffix first-invoice-ref
                          :purchase-order-reference purchase-order-reference)
         (db/transact-insert conn invoiceid))))

(defn printable-item [item]
  (spider (-> item :juxt.accounting/invoice-item-component)
          {:date [:juxt.accounting/_component first ; the parent entry
                  :juxt.accounting/date from-date (partial timeformat/unparse date-formatter)]
           :sortable-date [:juxt.accounting/_component first
                           :juxt.accounting/date from-date]
           :sortable-amount [:juxt.accounting/amount -]
           :description :juxt/description
           :amount (fn [item] (str (.getSymbol (as-currency (:juxt.accounting/currency item)))
                                   (str (:juxt.accounting/amount item))))}))

(defn print-invoice [{:keys [items subtotal vat total invoice-date issue-date
                             invoice-ref invoice-addressee invoice-address
                             client-name issuer currency purchase-order-reference
                             vat-rate]} out]
  (debugf "Printing invoice, number of items is %d" (count items))
  (let [currency-symbol (.getSymbol (as-currency currency))]
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
            [:chunk "Invoice date:"]]
           [:cell {:align :right}
            [:chunk (timeformat/unparse date-formatter invoice-date)]]]
          [[:cell {:align :right}
            [:chunk "Issue date:"]]
           [:cell {:align :right}
            [:chunk (timeformat/unparse date-formatter issue-date)]]]
          [[:cell {:align :right}
            [:chunk "Invoice ref:"]]
           [:cell {:align :right}
            [:chunk {:family :courier} invoice-ref]]]
          (when purchase-order-reference
            [[:cell {:align :right}
              [:chunk "Purchase order:"]]
             [:cell {:align :right}
              [:chunk {:family :courier} purchase-order-reference]]])
          [[:cell {:align :right}
            [:chunk "VAT number:"]]
           [:cell {:align :right}
            [:chunk {:family :courier} (-> issuer :vat-no)]]]
          [[:cell {:align :right}
            [:chunk "Account:"]]
           [:cell {:align :right}
            [:chunk {:family :courier} (-> issuer :bank-account-no)]]]
          [[:cell {:align :right}
            [:chunk "Sort-code:"]]
           [:cell {:align :right}
            [:chunk {:family :courier} (-> issuer :bank-sort-code)]]]]]]]

      `[:table {:border-width 1 :cell-border true :border true
                :widths [25 60 15]
                :header [{:color [255 255 255 255 255]} "Date" "Description" "Total"]}

        ~@(for [{:keys [date description amount sortable-amount]} (sort-by (juxt :sortable-date :sortable-amount) (map printable-item items))]
            [[:cell {:align :left} [:chunk date]]
             [:cell {:align :left} [:chunk description]]
             [:cell {:align :right} [:chunk amount]]])

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk "Subtotal"]]
         [:cell {:align :right} [:chunk ~(str currency-symbol subtotal)]]]

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk ~(str "VAT @ " (.format (java.text.DecimalFormat. "##") (* 100 vat-rate)) "%")]]
         [:cell {:align :right} [:chunk ~(str currency-symbol vat)]]]

        [[:cell {:align :left} [:chunk ""]]
         [:cell {:align :left} [:chunk {:style :bold} "TOTAL"]]
         [:cell {:align :right} [:chunk {:style :bold} ~(str currency-symbol total)]]]]

      [:spacer 2]
      [:paragraph
       [:phrase
        [:chunk "Please make payment within 30 days to our bank (account "]
        [:chunk {:family :courier} (-> issuer :bank-account-no)]
        [:chunk ", sort-code "]
        [:chunk {:family :courier} (-> issuer :bank-sort-code)]
        [:chunk  ")\nusing the reference " ]
        [:chunk {:family :courier} invoice-ref]
        [:chunk "\n\nMany thanks."]]]
      [:spacer 4]
      [:line {:dotted true}]
      [:spacer 1]
      [:chunk (apply str (interpose "\n" ((juxt :signatory :title :company-name) issuer)))]]

     out)))

(defn create-invoice-data-template [issuer-fields]
  {:pre [(every? (set (keys issuer-fields))
                 [:title :signatory :company-name :company-address :vat-no :bank-account-no :bank-sort-code])]}
  (fn create-invoice-data [db invoice {:keys [output-dir vat-rate]}]
    {:pre [(db/entity? invoice)]}
    (let [entity (d/entity db (:juxt.accounting/entity invoice))]
      (merge
       {:issuer issuer-fields
        :output-path (str output-dir java.io.File/separator
                          (:juxt.accounting/invoice-ref invoice) ".pdf")
        :vat-rate vat-rate
        }
       (spider entity
               {:client-name :juxt.accounting/name
                :invoice-addressee :juxt.accounting/invoice-addressee
                :invoice-address [:juxt.accounting/invoice-address edn/read-string]})
       (spider invoice
                {:invoice :db/id
                 :invoice-ref :juxt.accounting/invoice-ref
                 :invoice-date [:juxt.accounting/invoice-date from-date]
                 :issue-date [:juxt.accounting/issue-date from-date]
                 :items :juxt.accounting/item
                 :currency :juxt.accounting/currency
                 :subtotal :juxt.accounting/subtotal
                 :vat :juxt.accounting/vat
                 :total :juxt.accounting/total
                 :purchase-order-reference :juxt.accounting/purchase-order-reference
                 })))))

(defn generate-pdf-for-invoice [conn {:keys [invoice invoice-ref ^File output-path] :as invoice-data}]
  {:pre [(not (nil? invoice-ref))
         (not (nil? output-path))]}
  (let [f (file output-path)]
    (debugf "Printing PDF invoice to %s" f)
    (print-invoice invoice-data (io/output-stream f))
    @(d/transact conn [[:db/add invoice :juxt.accounting/pdf-file output-path]])
    #_(if (.exists f)
        (info "PDF invoice already exists, so will not overwrite: " f)
        (do
          (info "Printing PDF invoice to " f)
          (print-invoice invoice-data (FileOutputStream. f))))))
