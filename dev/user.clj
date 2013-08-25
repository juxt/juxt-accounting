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
(ns user
  )


;;
;; This namespace will be loaded on repl, test and run (that means both dev and prod) - like Pedestal

;; So developers need to issue the (dev) command as the first thing they do before starting the service.

;; TODO: Add a way of starting the prod service via the Leiningen repl. There should be analogs.

;; This indirection is in place because of this:
;;
;; "Some of my Relevance coworkers like this approach, others find it
;; too constraining. The Pedestal team uses pieces of this technique,
;; such as the :dev profile, but without tools.namespace. They were
;; annoyed that compiler errors prevented them from starting a new REPL,
;; so they came up with a variation that uses a function in user.clj to
;; load another file called dev.clj." --
;; http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded
(defn dev
  []
  (require 'dev)
  (in-ns 'dev)
  #_(dev/start))
