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
   [io.pedestal.service.http :as bootstrap]
   [io.pedestal.service.interceptor :refer (defbefore defhandler on-request defon-request definterceptorfn before handler)]
   [io.pedestal.service.http.route :as route]
   [io.pedestal.service.http.body-params :as body-params]
   [io.pedestal.service.http.route.definition :refer (defroutes expand-routes)]
   [ring.util
    [response :as ring-resp]
    [codec :as codec]]
   [jig.web
    [stencil :refer (get-template)]]
   [hiccup.core :refer (html h)]
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

(def menu
  [
   ;;["Views" ::views-page]
   ["Accounts" ::accounts-page]
   ;;["Clients" ::clients-page]
   ["Invoices" ::invoices-page]
   ["VAT Returns" ::vat-returns-page]
   ])

(defn render-page [{:keys [url-for system component] :as context} content]
  (let [route-name (-> context :route :route-name)
        title (ffirst
               (filter #(= route-name (second %)) menu))]
    (stencil/render
     (get-template system component "templates/page.html")
     {:ctx (let [ctx (get-in context [:component :jig.web/context])]
             (if (= ctx "/") "" ctx))
      :content (constantly (html (cons (when title [:h2 title]) content)))
      :home-href (url-for ::index-page)
      :app-name "JUXT Accounting"
      :title (or title "")
      :menu (for [[name link] menu]
              {:listitem
               (html [:li (when (= route-name link) {:class "active"})
                      [:a {:href (url-for link)} name]])})})))

(defn page-response [{:keys [url-for system] :as context} & content]
  (assoc context :response
         (ring-resp/response
          (render-page
           context
           content))))

(defbefore css-page [context]
  (assoc context
    :response
    (-> (ring-resp/response
         (css
          [:h1 :h2 :h3 {:color (rgb 0 0 154)}]
          [:td {:font-family "monospace" :font-size (pt 12)}]
          [:td.numeric {:text-align :right}]
          [:th.numeric {:text-align :right}]))
        (ring-resp/content-type "text/css"))))

(defbefore index-page
  [{:keys [url-for] :as context}]
  (page-response
   context
   [:h1 "Welcome to JUXT Accounts"]
   [:ul
    (for [[title link] menu]
      [:li [:a {:href (url-for link)} title]])]

   ;;[:pre (with-out-str (pprint (:route context)))]
   ))

(defbefore root-page
  [{:keys [request system url-for] :as context}]
  (assoc context :response
         (ring-resp/redirect (url-for ::index-page))))

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

(defbefore views-page
  [{:keys [request system url-for component] :as context}]
  (page-response
   context))

(defn account-link [url-for a]
  [:a {:href (url-for ::account-page :params {:account (keyword-formatter a)})} (keyword-formatter a)]
)

(defbefore accounts-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (page-response
     context
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
                        :formatters {:ident (fn [x] (account-link url-for (:ident x)))
                                     :balance #(moneyformat (:balance %) java.util.Locale/UK)}
                        :classes {:balance :numeric}}))
        #_[:p "Total balance (should be zero if all accounts reconcile): " (moneyformat (apply db/reconcile-accounts db (map :ident accounts)) java.util.Locale/UK)]
        ))
     )))

(defn to-ledger-view [db entries url-for]
  (->> entries (sort-by :date)
       (to-table {:column-order (explicit-column-order :date :description :other-account)
                  :hide-columns #{:entry :type :account :id}
                  :formatters {:date (comp date-formatter :date)
                               :value (fn [{:keys [value other-account]}]
                                        (moneyformat value java.util.Locale/UK))

                               :other-account
                               (fn [{:keys [other-account]}]
                                 [:a {:href (url-for ::account-page :params {:account (keyword-formatter (:db/ident other-account))})} (apply format "%s/%s" ((juxt namespace name) (:db/ident other-account)))]



                                        )}
                  :classes {:value "numeric"}
                  })))

;; (comp #(moneyformat % java.util.Locale/UK) :net-amount)

(defbefore account-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (page-response
     context
     (let [account (apply keyword (clojure.string/split (get-in request [:path-params :account]) #"/"))
           details (to-entity-map account db)
           entity (to-entity-map (:juxt.accounting/entity details) db)]
       (list [:h2 "Account"]
             [:dl
              (for [[dt dd]
                    (map vector ["Id" "Currency" "Entity" "Balance"]
                         (concat
                          ((juxt (comp str :db/ident) :juxt.accounting/currency) details)
                          ((juxt :juxt.accounting/name) entity)
                          [(moneyformat (db/get-balance db account) java.util.Locale/UK)]))]
                (list [:dt dt]
                      [:dd dd]))]

             (for [[title s type] [["Debits" "debits" :juxt.accounting/debit]
                                   ["Credits" "crebits" :juxt.accounting/credit]]]
               (let [components (db/get-account-components db account type)
                     entries (map second (group-by :entry components))]
                 (list
                  [:h3 title]
                  ;; We're showing the first component of the entry
                  ;; only, but add an indication there may be others.
                  ;; TODO We should still show the total value however.
                  (to-ledger-view db (map #(assoc (first %)
                                             :component-count (count %))

                                          entries) url-for)
                  (when (pos? (count entries))
                    [:p "Total: " (moneyformat (total (map :value components)) java.util.Locale/UK)])

                  ))))))))

(defbefore clients-page
  [{:keys [request system url-for component] :as context}]
  (page-response
   context))


(defbefore invoices-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (page-response
     context
     (let [invoices (db/get-invoices db)]
       (->> invoices
            (to-table {:formatters {:invoice-ref (fn [x] [:a {:href (url-for ::invoice-pdf-page :params {:invoice-ref (:invoice-ref x)})} (:invoice-ref x)])
                                    :invoice-date (comp date-formatter :invoice-date)
                                    :issue-date (comp date-formatter :issue-date)
                                    :output-tax-paid #(some-> % :output-tax-paid :juxt.accounting/date date-formatter)

                                    }
                       :hide-columns #{:invoice :items}
                       :column-order (explicit-column-order :invoice-ref :invoice-date :issue-date :entity-name :invoice :subtotal :vat :total)}))))))

(defbefore invoice-pdf-page
  [{:keys [request system] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))
        invoice-ref (get-in request [:path-params :invoice-ref])
        invoice (db/find-invoice-by-ref db invoice-ref)]
    (assoc context :response
           (-> (ring-resp/response
                (io/input-stream (:juxt.accounting/pdf-file (to-entity-map invoice db))))
               (ring-resp/content-type "application/pdf")))))

(defbefore vat-returns-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (page-response
     context
     (let [returns (db/get-vat-returns db)]
       (->> returns
            (to-table {:formatters {:date (comp date-formatter :date)}
                       :column-order (explicit-column-order :date :box1 :box6)}))))))

(definterceptorfn resources
  [name root-path & [opts]]
  (handler
   name
   (fn [req]
     (ring-resp/file-response
      (codec/url-decode (get-in req [:path-params :path]))
      {:root root-path, :index-files? true, :allow-symlinks? false}))))

(defn create-routes-terse [bootstrap-dist-dir jquery-dist-dir]
  {:pre [(string? bootstrap-dist-dir)
         (string? jquery-dist-dir)]}
  ["/" {:get root-page}
   ^:interceptors
   [(body-params/body-params)
    bootstrap/html-body]
   ["/index" {:get index-page}]
   ["/style.css" {:get css-page}]
;;   ["/views" {:get views-page}]
   ["/accounts" {:get accounts-page}]
   ["/accounts/*account" {:get account-page}]
;;   ["/clients" {:get clients-page}]
   ["/invoices" {:get invoices-page}]
   ["/invoice-pdfs/:invoice-ref" {:get invoice-pdf-page}]
   ["/vat-returns" {:get vat-returns-page}]
   ["/bootstrap/*path" {:get (resources ::bootstrap-resources bootstrap-dist-dir)}]
   ["/jquery/*path" {:get (resources ::jquery-resources jquery-dist-dir)}]

   ])
