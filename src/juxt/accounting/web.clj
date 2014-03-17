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
(ns juxt.accounting.web
  (:require
   jig
   [clojure.tools
    [trace :refer (deftrace)]
    [logging :refer :all]]
   [ring.util
    [response :as ring-resp]]
   [jig.bidi :refer (add-bidi-routes)]
   [bidi.bidi :refer (->Redirect ->WrapMiddleware path-for match-route)]
   [hiccup.core :refer (html h)]
   [ring.util.response :refer (file-response)]
   [ring.middleware.content-type :refer (wrap-content-type)]
   [ring.middleware.file-info :refer (wrap-file-info)]
   [clojure.pprint :refer (pprint)]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [juxt.accounting.database :as db]
   [juxt.accounting.money :refer (as-money)]
   [juxt.accounting.time :refer (to-local-date)]
   [datomic.api :as d]
   [garden.core :refer (css)]
   [garden.units :refer (px pt em percent)]
   [garden.color :refer (hsl rgb)]
   [stencil.core :as stencil]
   [juxt.datomic.extras :refer (to-entity-map)]
   [clojurewerkz.money.amounts :as ma :refer (total)]
   [clojurewerkz.money.format :refer (format) :rename {format moneyformat}])
  (:import (jig Lifecycle)))

(defn css-page [req]
  (-> (css
       [:h1 :h2 :h3 {:color (rgb 0 0 154)}]
       [:td {:font-family "monospace" :font-size (pt 12)}]
       [:td.numeric {:text-align :right}]
       [:th.numeric {:text-align :right}]
       [:div.container-narrow {:margin-left (pt 10) :font-size (pt 12)}]
       [:dt {:float :left}]
       [:dd {:margin-left (em 12 )}]
       [:p {:width (em 60)}]
       [:div.index {:padding-left (percent 20)
                    :padding-top (em 2)
                    }]
       )
      ring-resp/response
      (ring-resp/content-type "text/css")))

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

(defn date-formatter [d]
  {:pre [(instance? java.util.Date d)]}
  (.format (java.text.SimpleDateFormat. "y-MM-dd") d))

(defn accounts-table [req as-path db accounts]
  (list
   (->> accounts
        (sort-by :ident)
        (map #(assoc %
                :balance (db/get-balance db (:ident %))
                :component-count (db/count-account-components db (:ident %))))
        #_(remove (comp zero? :component-count))
        (to-table {:column-order (explicit-column-order :ident :entity-name :account-name :currency :balance)
                   :hide-columns #{:entity :component-count}
                   :formatters {:ident (fn [x]
                                         [:a {:href (as-path req :account :account-id (:ident x))} (name (:ident x))])
                                :balance #(moneyformat (:balance %) java.util.Locale/UK)}
                   :classes {:balance :numeric}}))
   #_[:p "Total balance (should be zero if all accounts reconcile): " (moneyformat (apply db/reconcile-accounts db (map :ident accounts)) java.util.Locale/UK)]))

(defn index-page [handlers]
  (fn [{routes :jig.bidi/routes :as req}]
    [:div.index
     [:h2 "Welcome to JUXT Accounting"]

     [:p "JUXT Accounting is an accounts program written by, and for,
     JUXT."]

     [:p "Like most accounting programs it is based on the centuries-old
     invention of " [:i "double-entry bookkeeping"] ". Every transaction
     in the system is recorded as both a credit in one account and a
     debit in another. There are no exceptions to this rule, and it is
     enforced rigourously by the database API."]

     [:p "Unlike most accounting programs, this program does not store
     state, nor provide a means of data entry. Transactions are recorded
     in files, using a simple plain-text format called " [:a
     {:href "https://github.com/edn-format/edn"} "EDN"] ". These files are editable using a standard
     text editor."]

     [:h3 "Accounts"]

     [:p "Click on the " [:a {:href (path-for routes (:accounts @handlers))} "Accounts"] " menu-item to view all the accounts. There are lots of accounts, too many really. That's where views come in."]

     ]))

(defn entities-page [dburi as-path]
  (fn [req]
    [:p "Entities page"]
    (let [db (d/db (d/connect dburi))]
      (list
       (to-table {:hide-columns #{}
                  :column-order (explicit-column-order :ident :name)
                  :formatters {:ident (fn [x] [:a {:href (as-path req :entity :entity (:ident x))} (:ident x)])}}
                 (->> (db/get-legal-entities-as-table db)
                      (sort-by :ident)))))))

(defn entity-page [dburi as-path]
  (fn [{{entity :entity :as rp} :route-params :as req}]
    (list
     (let [db (d/db (d/connect dburi))]
       (let [accounts (db/get-accounts-as-table db)]
         (accounts-table req as-path db (filter #(= (:entity %) entity) accounts)))
       ))))

(defn accounts-page [dburi as-path]
  (fn [req]
    (infof "keys of request are %s" (keys req))
    [:div
     [:p "Here is a list of ALL the accounts in the system. Click on any account to view the transactions."]
     (let [db (d/db (d/connect dburi))]
       (let [accounts (db/get-accounts-as-table db)]
         (accounts-table req as-path db accounts)))]))

(defn vat-ledger? [records]
  (every? (every-pred
           #(= 2 (count %))
           #(= #{:net :vat} (set (map :component-type %)))) records))

(defn single-component-records? [records]
  (every? #(= 1 (count %)) records))

(defn uk-money-format [value]
  (moneyformat value java.util.Locale/UK))

(defn to-vat-ledger [req records as-path]
  (to-table
   {:column-order (explicit-column-order :date :description :txdesc :net :other-account :vat :total)
    :hide-columns #{:other-account :vat-account}
    :formatters
    {:date (comp date-formatter :date)
     :net (fn [x]
            [:a {:href (as-path req :account :account-id (-> x :other-account :db/ident))}
             (-> x :net uk-money-format)])
     :vat (fn [x]
            [:a {:href (as-path req :account :account-id (-> x :vat-account :db/ident))}
             (-> x :vat uk-money-format)])
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

(defn to-ledger-view [req db as-path records]
  (cond
   (vat-ledger? records)
   (to-vat-ledger req (sort-by (comp :date first) records) as-path)
   (single-component-records? records)
   (->> records (map first) (sort-by :date)
        (to-table {:column-order (explicit-column-order :date :description :other-account)
                   :hide-columns #{:entry :type :account :id :other-account :invoice-item}
                   :formatters {:date (comp date-formatter :date)
                                :value (fn [{:keys [value other-account]}]
                                         [:a {:href (as-path req :account :account-id (-> other-account :db/ident))}
                                          (moneyformat value java.util.Locale/UK)])}
                   :classes {:value "numeric"}}))
   :otherwise
   [:pre (with-out-str (clojure.pprint/pprint records))]))

(defn account-page [dburi as-path]
  (fn this [req]
    (println "Account page requested!")
    (let [db (d/db (d/connect dburi))]
      (let [account (keyword (get-in req [:route-params :account-id]))
            details (to-entity-map account db)
            entity (to-entity-map (:juxt.accounting/entity details) db)]
        (list [:h2 "Account"]
              [:dl
               (for [[dt dd]
                     (map vector ["Id" "Currency" "Entity" "Balance"]
                          (list
                           (name (:db/ident details))
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
                   (to-ledger-view req db as-path entries)  ;; TODO
                   (when (pos? (count entries))
                     [:p "Total: " (moneyformat (total (map :value components)) java.util.Locale/UK)])))))))))


(defn views-page [{:keys [views]} as-path]
  (fn this [req]
    (let [routes (:jig.bidi/routes req)]
      (->> views
           (map (fn [[k v]](assoc v :view-id (name k))))
           (to-table
            {:formatters
             {:label (fn [x] [:a {:href (as-path req :view :view-id (:view-id x))} (:label x)])}
             :hide-columns #{:accounts :view-id}
             :column-order (explicit-column-order :invoice-ref :invoice-date :issue-date :entity-name :invoice :subtotal :vat :total)})))))

(defn view-pred [pat-list]
  (fn [account]
    (infof "Account to filter is %s" account)
    (some #(re-matches (re-pattern %) (-> account :ident name)) pat-list)))

(defn view-page [{:keys [views]} dburi as-path]
  (fn [req]
    (let [db (d/db (d/connect dburi))]
      (let [view (get views (-> req :route-params :view-id keyword))
            accounts (filter (view-pred (:accounts view)) (db/get-accounts-as-table db))]
        (accounts-table req as-path db accounts)))
    ))

(defn invoices-page [dburi as-path]
  (fn this [req]
    (let [db (d/db (d/connect dburi))
          routes (:jig.bidi/routes req)
          invoices (db/get-invoices db)]
      (->> invoices
           (sort-by :invoice-ref)
           (to-table
            {:formatters
             {:invoice-ref
              (fn [x] [:a {:href (as-path req :invoice :invoice-ref (:invoice-ref x))} (:invoice-ref x)])
              :invoice-date (comp date-formatter :invoice-date)
              :issue-date (comp date-formatter :issue-date)
              :vat-paid #(some-> % :vat-paid :juxt.accounting/date date-formatter)

              }
             :hide-columns #{:invoice :items}
             :column-order (explicit-column-order :invoice-ref :invoice-date :issue-date :entity-name :invoice :subtotal :vat :total)})))))

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

(defn export [dburi as-path]
  (fn [req]
    (html
     [:ul
      [:li [:a {:href (as-path req :export-balances)} "Account balances"]]
      [:li [:a {:href (as-path req :export-transactions)} "All transactions"]]])))

(defn export-balances [dburi as-path]
  (fn [req]
    {:status 200
     :headers {"content-type" "text/plain;charset=utf-8"}
     :body
     (let [db (d/db (d/connect dburi))]
       (let [accounts (db/get-accounts-as-table db)]
         (->> accounts
              (sort-by :ident)
               (map #(assoc %
                      :balance (db/get-balance db (:ident %))
                      :debits (count (db/get-account-components db (:ident %) :juxt.accounting/debit))
                      :credits (count (db/get-account-components db (:ident %) :juxt.accounting/credit))
                      :item-count (db/count-account-components db (:ident %))))
              (map (juxt :entity-name (comp name :ident) :debits :credits :currency (comp #(.getAmount %) :balance)))
              (cons ["Legal entity" "Unique account identifier" "Number of debits" "Number of credits" "Currency" "Balance"])
              (map #(apply str (interpose "," %)))
              (interpose "\n")
              (apply str))))}))

(defn export-transactions [dburi]
  (fn [req]
    {:status 200
     :headers {"content-type" "text/plain;charset=utf-8"}
     :body
     (let [db (d/db (d/connect dburi))]
       (->> (db/get-all-components db :juxt.accounting/debit)
            (sort-by :date)
            (map (juxt (comp to-local-date :date)
                       (comp name :db/ident :account)
                       (comp name :db/ident :other-account)
                       :description
                       (comp #(.getAmount %) :value)))
            (cons ["Date" "Debit account" "Credit account" "Description" "Value"])
              (map #(apply str (interpose "," %)))
              (interpose "\n")
              (apply str)))}))

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
                   :title-link "/"
                   :content content
                   :app-name "JUXT Accounting"
                   :menu (for [[label handler] menu]
                           {:listitem (html [:li (when (= (:uri req) (path-for routes handler)) {:class "active"}) [:a {:href (path-for routes handler)} label]])})})
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

(defn wrap-promise [p]
  (letfn [(ensure-realized [] (assert (realized? p) "Cannot lookup until deref is realized"))]
    (reify
      clojure.lang.ILookup
      (valAt [_ k]
        (ensure)
        (apply get @p k))
      (valAt [_ k not-found]
        (ensure)
        (apply get @p k not-found)))))

(defn create-handlers [data dburi]
  (let [p (promise)
        as-path (fn [req k & args]
                  (assert (realized? p))
                  (apply path-for (:jig.bidi/routes req) (k @p) args))]
    @(deliver p {:index (index-page p)
                 :entities (entities-page dburi as-path)
                 :entity (entity-page dburi as-path)
                 :accounts (accounts-page dburi as-path)
                 :account (account-page dburi as-path)
                 :views (views-page data as-path)
                 :view (view-page data dburi as-path)
                 :invoices (invoices-page dburi as-path)
                 :invoice (invoice-pdf-page dburi)
                 :vat-returns (vat-returns-page dburi)
                 :export (export dburi as-path)
                 :export-balances (export-balances dburi as-path)
                 :export-transactions (export-transactions dburi)})))


(defn create-bidi-routes
  [{bootstrap-dist-dir :bootstrap-dist
    jquery-dist-dir :jquery-dist
    dburi :dburi
    template-loader :template-loader
    data :data}]
  (let [handlers (create-handlers data dburi)]
    ["/"
     [
      ["" (->Redirect 307 (:index handlers))]
      ["accounts" (->Redirect 307 (:accounts handlers))]
      ["views" (->Redirect 307 (:views handlers))]
      ["invoices" (->Redirect 307 (:invoices handlers))]

      [["invoice-pdfs/" :invoice-ref] (:invoice handlers)]
      ["" (->WrapMiddleware
           [
            ["index" (:index handlers)]
            ["entities/" (:entities handlers)]
            [["entities/" [keyword :entity]] (:entity handlers)]
            ["accounts/" (:accounts handlers)]
            [["accounts/" [keyword :account-id]] (:account handlers)]
            ["views/" (:views handlers)]
            [["views/" :view-id] (:view handlers)]
            ["invoices/" (:invoices handlers)]
            ["vat-returns/" (:vat-returns handlers)]
            ["export/" (:export handlers)]
            ]
           (partial boilerplate template-loader [["Entities" (:entities handlers)]
                                                 ["Accounts" (:accounts handlers)]
                                                 ["Views" (:views handlers)]
                                                 ["Invoices" (:invoices handlers)]
                                                 ["VAT" (:vat-returns handlers)]
                                                 ["Export" (:export handlers)]
                                                 ]))]
      ["export/" [["balances" (:export-balances handlers)]
                  ["transactions" (:export-transactions handlers)]
                  ]]

      ["style.css" css-page]
      ["jquery/" (->Files {:dir (str jquery-dist-dir "/")})]
      ["bootstrap/" (->Files {:dir (str bootstrap-dist-dir "/")})]]]))

(def is-directory (every-pred identity (memfn exists) (memfn isDirectory)))

(deftype Website [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (infof "Initializing Website: %s" (:jig/id config))
    (let [dburi (:dburi system)
          template-loader (get-in system [(:jig/id (jig.util/satisfying-dependency system config 'jig.stencil/StencilLoader)) :jig.stencil/loader])
          ]
      (doseq [k [:bootstrap-dist :jquery-dist]]
        (when-not (is-directory (some-> config k io/file))
          (throw (ex-info (format "Dist dir for %s not valid: %s" (name k) (-> config k)) {}))))

      (-> system
          (assoc-in [(:jig/id config) :data]
                    (get-in system [:jig/config :jig/components (:juxt.accounting/data config)]))

          ;;(link-to-stencil-loader config)

          (add-bidi-routes config
                           (create-bidi-routes
                            (merge config
                                   {:dburi dburi
                                    :template-loader template-loader
                                    :data (:data system)}))))))
  (stop [_ system] system))
