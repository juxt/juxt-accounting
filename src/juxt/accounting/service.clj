;; Copyright © 2013, JUXT LTD. All Rights Reserved.
;;
;; This file is part of JUXT Accounting.
;;
;; JUXT Accounting is free software: you can redistribute it and/or modify it under the
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
(ns juxt.accounting.service
  (:require
   [clojure.tools
    [trace :refer (deftrace)]
    [logging :refer :all]]
   [ring.util
    [response :as ring-resp]
    [codec :as codec]]
   [bidi.bidi :refer (->Redirect ->WrapMiddleware path-for)]
   [hiccup.core :refer (html h)]
   [ring.util.response :refer (file-response)]
   [ring.middleware.content-type :refer (wrap-content-type)]
   [ring.middleware.file-info :refer (wrap-file-info)]
   [clojure.pprint :refer (pprint)]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [juxt.accounting.database :as db]
   [juxt.accounting.money :refer (as-money)]
   [datomic.api :as d]
   [garden.core :refer (css)]
   [garden.units :refer (px pt em percent)]
   [garden.color :refer (hsl rgb)]
   [stencil.core :as stencil]
   [juxt.datomic.extras :refer (to-entity-map)]
   [clojurewerkz.money.amounts :as ma :refer (total)]
   [clojurewerkz.money.format :refer (format) :rename {format moneyformat}]))

(defn css-page [req]
  (-> (css
       [:h1 :h2 :h3 {:color (rgb 0 0 154)}]
       [:td {:font-family "monospace" :font-size (pt 12)}]
       [:td.numeric {:text-align :right}]
       [:th.numeric {:text-align :right}])
      ring-resp/response
      (ring-resp/content-type "text/css")))

(defn index-page [request]
  (ring-resp/response (html [:h1 "Welcome to JUXT Accounts"])))

;; Don't spend too much work on this, it's just a service and rendering
;; will be done in the Pedestal app.
;; column order is really just a hack to get balances on the right
(defn to-table
  ([{:keys [column-order hide-columns formatters classes] :or {column-order (constantly 0)} :as options} rows]
     (if-not (empty? rows)
       (let [ks (sort-by column-order
                         (-> (set (keys (first rows)))
                             (set/union (set (keys formatters)))
                             (set/difference  hide-columns)))]
         [:table.table
          [:thead
           [:tr
            (for [k ks]
              [:th {:class (k classes)} (name k)])]]
          [:tbody
           (for [row rows]
             (cond->>
              [:tr
               (for [k ks]
                 [:td {:class (k classes)}
                  ((or (k formatters) k) row)])]))]])
       [:p "(No data)"]))
  ([rows] (to-table {} rows)))

(defn explicit-column-order [& keys]
  (fn [x] (count (take-while (comp not (partial = x)) keys))))

(defn get-dburi [context]
  (get-in (:system context) [:jig/config :jig/components (:juxt.accounting/data (:component context)) :db :uri]))

(defn keyword-formatter [x]
  {:pre [x]}
  (if (keyword? x)
    (format "%s/%s" (namespace x) (name x))
    (str x)))

(defn date-formatter [d]
  {:pre [(instance? java.util.Date d)]}
  (.format (java.text.SimpleDateFormat. "y-MM-dd") d))

(defn account-link [routes target account]
  [:a {:href (path-for routes target :account (keyword-formatter account))} (keyword-formatter account)])

(defn accounts-page [dburi account-page]
  (fn [req]
    (infof "keys of request are %s" (keys req))
    (let [db (d/db (d/connect dburi))]
      (let [accounts (db/get-accounts-as-table db)]
        (list
         (->> accounts
              (sort-by :ident)
              (map #(assoc %
                      :balance (db/get-balance db (:ident %))
                      :component-count (db/count-account-components db (:ident %))))
              (remove (comp zero? :component-count))
              (to-table {:column-order (explicit-column-order :ident :entity-name :account-name :currency :balance)
                         :hide-columns #{:entity :component-count}
                         :formatters {:ident (fn [x] (account-link (:jig.bidi/routes req) account-page (:ident x)))
                                      :balance #(moneyformat (:balance %) java.util.Locale/UK)}
                         :classes {:balance :numeric}}))
         #_[:p "Total balance (should be zero if all accounts reconcile): " (moneyformat (apply db/reconcile-accounts db (map :ident accounts)) java.util.Locale/UK)]
         )))))

(defn vat-ledger? [records]
  (every? (every-pred
           #(= 2 (count %))
           #(= #{:net :vat} (set (map :component-type %)))) records))

(defn single-component-records? [records]
  (every? #(= 1 (count %)) records))

(defn uk-money-format [value]
  (moneyformat value java.util.Locale/UK))

(defn account-link2 [routes target acct text]
  [:a {:href (path-for routes target :account acct)} text])

(defn to-vat-ledger [url-for records]
  (to-table
   {:column-order (explicit-column-order :date :description :txdesc :net :other-account :vat :total)
    :hide-columns #{:other-account :vat-account}
    :formatters
    {:date (comp date-formatter :date)
     :net (comp (partial apply account-link2 url-for) (juxt :other-account (comp uk-money-format :net)))
     :vat (comp (partial apply account-link2 url-for) (juxt :vat-account (comp uk-money-format :vat)))
     :total (comp uk-money-format :total)
     }
    :classes
    {:net "numeric"
     :vat "numeric"
     :total "numeric"}}

   (let [order [:net :vat]]
     (for [rec (map (comp (partial zipmap order)
                          #(sort-by (comp (into {} (map vector order (range)))
                                          :component-type)
                                    %))
                    records)]
       (reduce-kv
        (fn [acc k v] (assoc acc k (v rec)))
        {}
        {:date #(get-in % [:net :date])
         :description #(get-in % [:net :description])
         :txdesc #(get-in % [:net :txdesc])
         :net #(get-in % [:net :value])
         :other-account #(get-in % [:net :other-account])
         :vat #(get-in % [:vat :value])
         :vat-account #(get-in % [:vat :other-account])
         :total #(total [(get-in % [:net :value]) (get-in % [:vat :value])])
         })))))

(defn to-ledger-view [db target records routes]
  (cond
   (vat-ledger? records)
   (comment (to-vat-ledger url-for (sort-by (comp :date first) records)))
   (single-component-records? records)
   (->> records (map first) (sort-by :date)
        (to-table {:column-order (explicit-column-order :date :description :other-account)
                   :hide-columns #{:entry :type :account :id :other-account :invoice-item}
                   :formatters {:date (comp date-formatter :date)
                                :value (fn [{:keys [value other-account]}]
                                         (account-link2 routes target (keyword-formatter (:db/ident other-account)) (moneyformat value java.util.Locale/UK)))}
                   :classes {:value "numeric"}}))
   :otherwise
   [:pre (with-out-str (clojure.pprint/pprint records))]))

(defn account-page [dburi]
  (fn this [req]
    (let [db (d/db (d/connect dburi))]
      (let [account (keyword (get-in req [:route-params :account]))
            details (to-entity-map account db)
            entity (to-entity-map (:juxt.accounting/entity details) db)]
        (list [:h2 "Account"]
              [:dl
               (for [[dt dd]
                     (map vector ["Id" "Currency" "Entity" "Balance"]
                          (list
                           (keyword-formatter (:db/ident details))
                           (:juxt.accounting/currency details)
                           (:juxt.accounting entity)
                           (moneyformat (db/get-balance db account) java.util.Locale/UK)
                           ))]
                 (list [:dt dt]
                       [:dd dd]))]

              (for [[title s type] [["Debits" "debits" :juxt.accounting/debit]
                                    ["Credits" "crebits" :juxt.accounting/credit]]]
                (let [components (db/get-account-components db account type)
                      entries (map second (group-by :entry components))]
                  (list
                   [:h3 title]
                   (to-ledger-view db this entries (:jig.bidi/routes req))
                   (when (pos? (count entries))
                     [:p "Total: " (moneyformat (total (map :value components)) java.util.Locale/UK)])))))))))

(defn invoices-page [dburi target]
  (let [db (d/db (d/connect dburi))]
    (fn this [req]
      (let [routes (:jig.bidi/routes req)
            invoices (db/get-invoices db)]
        (->> invoices
             (to-table
              {:formatters
               {:invoice-ref
                (fn [x] [:a {:href (path-for routes target :invoice-ref (:invoice-ref x))} (:invoice-ref x)])
                :invoice-date (comp date-formatter :invoice-date)
                :issue-date (comp date-formatter :issue-date)
                :output-tax-paid #(some-> % :output-tax-paid :juxt.accounting/date date-formatter)

                }
               :hide-columns #{:invoice :items}
               :column-order (explicit-column-order :invoice-ref :invoice-date :issue-date :entity-name :invoice :subtotal :vat :total)}))))))

(defn invoice-pdf-page [dburi]
  (fn [req]
    (let [db (d/db (d/connect dburi))
          invoice-ref (get-in req [:route-params :invoice-ref])
          invoice (db/find-invoice-by-ref db invoice-ref)]
      (-> (ring-resp/response
                     (io/input-stream (:juxt.accounting/pdf-file (to-entity-map invoice db))))
          (ring-resp/content-type "application/pdf")))))

(defn vat-returns-page [dburi]
  (fn [req]
    (let [db (d/db (d/connect dburi))]
      (let [returns (db/get-vat-returns db)]
        (->> returns
             (to-table {:formatters {:date (comp date-formatter :date)}
                        :column-order (explicit-column-order :date :box1 :box6)}))))))

;; TODO - use the one in the latest bidi release
(defrecord Files [options]
  bidi.bidi.Matched
  (resolve-handler [this m]
    (assoc (dissoc m :remainder)
      :handler (-> (fn [req] (file-response (:remainder m) {:root (:dir options)}))
                   (wrap-file-info (:mime-types options))
                   (wrap-content-type options))))
  (unresolve-handler [this m] nil))

(defn boilerplate [template-loader menu h]
  (fn [req]
    (let [routes (:jig.bidi/routes req)]
      (let [content (-> (h req) html)]
        (->
         (stencil/render (template-loader "templates/page.html")
                  {:title "JUXT Accounting"
                   :content content
                   :menu (for [[label handler] menu]
                           {:listitem (html [:li [:a {:href (path-for routes handler)} label]])})})
         ring-resp/response
         (ring-resp/content-type "text/html")
         (ring-resp/charset "utf-8"))))))

(defn hiccup-debug [h]
  (fn [req]
    (->
     (h req)
     clojure.pprint/pprint
     with-out-str
     ring-resp/response
     (ring-resp/content-type "text/plain")
     (ring-resp/charset "utf-8"))))

(defn create-bidi-routes
  [{bootstrap-dist-dir :bootstrap-dist
    jquery-dist-dir :jquery-dist
    dburi :dburi
    template-loader :template-loader}]

  {:pre [(string? bootstrap-dist-dir)
         (string? jquery-dist-dir)
         (string? dburi)]}

  (let [account-page (account-page dburi)
        accounts-page (accounts-page dburi account-page)
        invoice-pdf-page (invoice-pdf-page dburi)
        invoices-page (invoices-page dburi invoice-pdf-page)
        vat-returns-page (vat-returns-page dburi)

        menu [["Accounts" accounts-page]
              ["Invoices" invoices-page]
              ["VAT Returns" vat-returns-page]]]
    ["/"
     [
      ["" (->Redirect 307 index-page)]
      ["index" index-page]

      ["accounts" (->Redirect 307 accounts-page)]
      ["invoices" (->Redirect 307 invoices-page)]

      [["invoice-pdfs/" :invoice-ref] invoice-pdf-page]

      ["" (->WrapMiddleware
           [["accounts/" accounts-page]
            [["accounts/" :account] account-page]
            ["invoices/" invoices-page]
            ["vat-returns/" vat-returns-page]
            ]
           (partial boilerplate template-loader menu))]

      ["style.css" css-page]

      ["bootstrap/" (->Files {:dir (str bootstrap-dist-dir "/")})]
      ["jquery/" (->Files {:dir (str jquery-dist-dir "/")})]
      ]]))
