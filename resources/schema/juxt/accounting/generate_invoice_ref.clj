;; This is a database function to generate an incrementing invoice reference

;; We could do more of the algorithm in Clojure, but it's a fun
;; demonstration of logic-programming in a Datomic query. Of course, the
;; relationships are only forward-traversable, this isn't quite
;; core.logic magic!

[[:db/add invoice :juxt.accounting/invoice-ref
  (let [s (ffirst
           (reverse
            (sort
             (datomic.api/q
              '[:find ?np :in $ ?prefix ?init
                :where
                [_ :juxt.accounting/invoice-ref ?ref]
                [(.startsWith ^String ?ref ?prefix)]
                [(count ?prefix) ?len]
                [(.substring ^String ?ref ?len) ?s]
                [(Integer/parseInt ?s) ?si]
                [(inc ?si) ?sn]
                [(count ?init) ?w] ; 0-padded length of our incrementing number
                [(str "%0" ?w "d") ?fs] ; eg. %03d - for padding with 0s
                [(format ?fs ?sn) ?np]]
              db prefix init))))]
    (str prefix (or s init))
    )]]
