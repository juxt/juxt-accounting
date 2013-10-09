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
(ns pro.juxt.accounting.service
  (:require
   [clojure.tools
    [trace :refer (deftrace)]]
   [io.pedestal.service.http :as bootstrap]
   [io.pedestal.service.interceptor :refer (defbefore defhandler on-request defon-request definterceptorfn before)]
   [io.pedestal.service.http.route :as route]
   [io.pedestal.service.http.body-params :as body-params]
   [io.pedestal.service.http.route.definition :refer (defroutes expand-routes)]
   [ring.util.response :as ring-resp]
   [hiccup.core :as hiccup]
   [clojure.pprint :refer (pprint)]
   [clojure.tools.logging :refer :all]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [pro.juxt.accounting.database :as db]
   [datomic.api :as d]
   [garden.core :refer (css)]
   [garden.units :refer (px pt em percent)]
   [garden.color :refer (hsl rgb)]
   [clojurewerkz.money.format :refer (format) :rename {format moneyformat}]))

(defmacro menu [url-for]
  `[:nav
    [:ul
     [:li [:a {:href (~url-for ::index-page)} "Home"]]
     [:li [:a {:href (~url-for ::accounts-page)} "Accounts"]]
     [:li [:a {:href (~url-for ::invoices-page)} "Invoices"]]
     [:li [:a {:href (~url-for ::vat-returns-page)} "VAT Returns"]]
     ;;     [:li [:a {:href "/expenses"} "Expenses"]]
     ;;     [:li [:a {:href "/calendar"} "Calendar"]]
     [:li [:a {:href (~url-for ::settings-page)} "Settings"]]
     [:li [:a {:href (~url-for ::about-page)} "About"]]
     ]])

(defmacro html [{:keys [title system url-for component]} & body]
  `(hiccup/html
    [:html
     [:head
      [:title ~title]
      [:link {:rel "stylesheet" :href (~url-for ::css-page)}]
      ]
     [:body
      (menu ~url-for)
      ~@body
      [:hr {:align :center}]
      [:p "Data sourced from " [:code (get-in ~system [:jig/config :jig/components (:pro.juxt.accounting/data ~component) :accounts-file])]]
      ;;[:p "Component is " [:pre (with-out-str (pprint ~component))]]
      ;;[:p "System is " [:pre (with-out-str (pprint ~system))]]
      ]]))

(defbefore css-page [context]
  (assoc context
    :response
    (-> (ring-resp/response
         (css
          [:h1 :h2 :h3 {:color (rgb 0 0 255)}]
          [:table {:border-collapse :collapse}]
          [:th :td {:border [(px 1) :solid (rgb 0 0 0)]}]
          [:th :td {:padding [(px 2) (em 0.5)]}]
          [:td {:white-space :nowrap}]
          [:td.span {:white-space :normal :width (percent 100)}]
          [:nav [:ul {:list-style-type :none
                      :margin 0
                      :padding 0}
                 [:li {:display :inline :padding (px 10)}]]]
          [:hr {:margin [(pt 26) (percent 25)] :width (percent 50)}]
          [:td.numeric {:text-align :right :font-family "monospace" :font-size (pt 14)} ]))
        (ring-resp/content-type "text/css"))))

(defhandler about-page
  [request]
  (ring-resp/response (format "Clojure %s" (clojure-version))))

(defbefore index-page
  [{:keys [url-for system component] :as context}]
  (assoc context
    :response
    (ring-resp/response
     (html {:title "JUXT Accounts"
            :system system
            :url-for url-for
            :component component}
           [:h1 "Index"]
           [:p "Welcome to JUXT Accounts"]
           ))))

(defbefore root-page
  [{:keys [request system url-for] :as context}]
  (assoc context :response
         (ring-resp/redirect (url-for ::index-page))))

;; Don't spend too much work on this, it's just a service and rendering
;; will be done in the Pedestal app.
;; column order is really just a hack to get balances on the right


(defn to-table
  ([{:keys [column-order hide-columns formatters classes] :or {column-order (constantly 0)} :as options} rows]
     (let [ks (sort-by column-order (set/difference (set (keys (first rows))) hide-columns))]
       [:table
        [:thead
         [:tr
          (for [k ks]
            [:th k])]]
        [:tbody
         (for [row rows]
           (cond->>
            [:tr
             (for [k ks]
               [:td {:class (k classes)}
                ((or (k formatters) k) row)])]))]]))
  ([rows] (to-table {} rows)))

(defn explicit-column-order [& keys]
  (fn [x] (count (take-while (comp not (partial = x)) keys))))

(defn get-dburi [context]
  (get-in (:system context) [:jig/config :jig/components (:pro.juxt.accounting/data (:component context)) :db :uri]))

(defn keyword-formatter [x]
  (format "%s/%s" (namespace x) (name x)))

(defn date-formatter [d]
  {:pre [(instance? java.util.Date d)]}
  (.format (java.text.SimpleDateFormat. "y-MM-dd") d))

(defn account-link [url-for a]
  [:a {:href (url-for ::account-page :params {:account (keyword-formatter a)})} (keyword-formatter a)]
)

(defbefore accounts-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (assoc context :response
           (ring-resp/response
            (html
             {:title "Accounts"
              :system system
              :url-for url-for
              :component component
              }
             [:h2 "Accounts"]
             (let [accounts (db/get-accounts db)]
               (list
                (->> accounts
                     (sort-by :ident)
                     (map #(assoc % :balance (db/get-balance db (:account %))))
                     (to-table {:column-order (explicit-column-order :ident :entity-name :type :href :balance)
                                :hide-columns #{:entity-ident :entity :account :currency}
                                :formatters {:ident (fn [x] (account-link url-for (:ident x)))
                                             :balance #(moneyformat (:balance %) java.util.Locale/UK)}
                                :classes {:balance :numeric}}))
                [:p "Total balance (should be zero if all accounts reconcile): " (moneyformat (apply db/reconcile-accounts db (map :account accounts)) java.util.Locale/UK)]
                ))
             )))))

(deftrace to-ledger-view [db entries url-for]
  (->> entries (sort-by :date)
       (to-table {:column-order (explicit-column-order :date :description :twin-account :amount)
                  :hide-columns #{:entry :account :tx :type}
                  :formatters {:amount (comp #(moneyformat % java.util.Locale/UK) :amount)
                               :date (comp date-formatter :date)
                               :twin-account (comp (partial account-link url-for) :twin-account)}
                  :classes {:description "span"
                            :amount "right"}})))

(defbefore account-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (assoc context :response
           (ring-resp/response
            (let [account (apply keyword (clojure.string/split (get-in request [:path-params :account]) #"/"))
                  details (db/to-entity-map account db)
                  entity (db/to-entity-map (:pro.juxt.accounting/entity details) db)]
              (html {:title "Account detail"
                     :system system
                     :url-for url-for
                     :component component}
               [:h2 "Account"]
               [:dl
                (for [[dt dd]
                      (map vector ["Id" "Currency" "Entity" "Balance"]
                           (concat
                            ((juxt (comp str :db/ident) :pro.juxt.accounting/currency) details)
                            ((juxt :pro.juxt.accounting/name) entity)
                            [(moneyformat (db/get-balance db account) java.util.Locale/UK)]))]
                  (list [:dt dt]
                        [:dd dd]))]

               [:h3 "Debits"]
               [:pre (to-ledger-view db (db/get-debits db account) url-for)]
               [:p "Total debit: " (moneyformat (db/get-total-debit db account) java.util.Locale/UK)]
               [:h3 "Credits"]
               [:pre (to-ledger-view db (db/get-credits db account) url-for)]
               [:p "Total credit: " (moneyformat (db/get-total-credit db account) java.util.Locale/UK)]))))))

(defbefore invoices-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (assoc context :response
           (ring-resp/response
            (html {:title "Invoices"
                   :system system
                   :url-for url-for
                   :component component}
             [:h2 "Invoices"]
             (let [invoices (db/get-invoices db)]
               (->> invoices
                    (to-table {:formatters {:invoice-ref (fn [x] [:a {:href (url-for ::invoice-pdf-page :params {:invoice-ref (:invoice-ref x)})} (:invoice-ref x)])
                                            :invoice-date (comp date-formatter :invoice-date)
                                            :issue-date (comp date-formatter :issue-date)
                                            :output-tax-paid #(some-> % :output-tax-paid :pro.juxt.accounting/date date-formatter)
                                            }
                               :hide-columns #{:invoice}
                               :column-order (explicit-column-order :invoice-ref :invoice-date :issue-date :entity-name :invoice :subtotal :vat :total)}))))
            ))))

(defbefore invoice-pdf-page
  [{:keys [request system] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))
        invoice-ref (get-in request [:path-params :invoice-ref])
        invoice (db/find-invoice-by-ref db invoice-ref)]
    (assoc context :response
           (-> (ring-resp/response
                (io/input-stream (:pro.juxt.accounting/pdf-file (db/to-entity-map invoice db))))
               (ring-resp/content-type "application/pdf")))))

(defbefore vat-returns-page
  [{:keys [request system url-for component] :as context}]
  (let [dburi (get-dburi context)
        db (d/db (d/connect dburi))]
    (assoc context :response
           (ring-resp/response
            (html {:title "VAT Returns"
                   :system system
                   :url-for url-for
                   :component component
                   }
             [:h2 "VAT Returns"]
             (let [returns (db/get-vat-returns db)]
               (->> returns
                    (to-table {:formatters {:date (comp date-formatter :date)}
                               :column-order (explicit-column-order :date :box1 :box6)}))))))))

(defbefore settings-page
  [context]
  (assoc context
    :response (ring-resp/response
               (hiccup/html
                [:p "Context is..."]
                [:pre (with-out-str (pprint context))]
                ))))

(defn create-routes-terse [system]
  ["/" {:get root-page}
   ^:interceptors
   [(body-params/body-params)
    bootstrap/html-body]
   ["/index" {:get index-page}]
   ["/style.css" {:get css-page}]
   ["/accounts" {:get accounts-page}]
   ["/accounts/*account" {:get account-page}]
   ["/invoices" {:get invoices-page}]
   ["/invoice-pdfs/:invoice-ref" {:get invoice-pdf-page}]
   ["/vat-returns" {:get vat-returns-page}]
   ["/about" {:get about-page}]
   ["/settings" {:get settings-page}]
   ])

(defn create-routes [system]
  (expand-routes
   [(create-routes-terse system)]))

;; Consumed by accounting-service.server/create-server
(defn create-service [system]
  {:env :prod
   ;; You can bring your own non-default interceptors. Make
   ;; sure you include routing and set it up right for
   ;; dev-mode. If you do, many other keys for configuring
   ;; default interceptors will be ignored.
   ;; :bootstrap/interceptors []
   ::bootstrap/routes (create-routes system)

   ;; Uncomment next line to enable CORS support, add
   ;; string(s) specifying scheme, host and port for
   ;; allowed source(s):
   ;;
   ;; "http://localhost:8080"
   ;;
   ;;::boostrap/allowed-origins ["scheme://host:port"]

   ;; Root for resource interceptor that is available by default.
   ::bootstrap/resource-path "/public"

   ;; Either :jetty or :tomcat (see comments in project.clj
   ;; to enable Tomcat)
   ;;::bootstrap/host "localhost"
   ::bootstrap/type :jetty
   ::bootstrap/port 8080})
