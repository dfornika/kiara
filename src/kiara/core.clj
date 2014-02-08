(ns kiara.core
  "Operations for initializing, loading, and querying a database.
  WARNING: No retrying or graceful fallbacks"
  (:require [kiara.core-schema :as cschema]
            [kiara.schema :as schema]
            [kiara.data :as data]
            [crg.turtle.qname :as qname]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :refer [q] :as d]
            [kiara.types :refer [Kiara DatomicUrlString UriString PartialUriString
                                 UriString? Triple] :as t]
            [schema.core :as s])
  (:import [datomic Peer]
           [datomic.db Db]
           [datomic.peer Connection]
           [java.net URI]))

(def kiara-ns "http://raw.github.com/quoll/kiara/master/ns#")

(s/defn rdb :- Db
  "Gets the current db value on a connection. This may be expanded for reliability."
  [connection :- Connection]
  (d/db connection))

(s/defn update-dbname :- DatomicUrlString
  "Updates the dbname in a Datomic database URL."
  [uri :- DatomicUrlString, dbname :- String]
  (condp re-matches uri
    #"datomic:ddb://([^/]*)/([^/]*)/([^?]*)(\\?.*)?" :>>
      (fn [[_ region table _ query]] (str "datomic:ddb://" region "/" table "/" dbname query))
    #"datomic:riak://([^/]*)/([^/]*)/([^?]*)(\\?.*)?" :>>
      (fn [[_ host bucket _ query]] (str "datomic:riak://" host "/" bucket "/" dbname query))
    #"datomic:couchbase://([^/]*)/([^/]*)/([^?]*)(\\?.*)?" :>>
      (fn [[_ host bucket _ query]] (str "datomic:couchbase://" host "/" bucket "/" dbname query))
    #"datomic:sql://([^?]*)?(\\?.*)?" :>>
      (fn [[_ _ jdbc-url]] (str "datomic:sql://" dbname jdbc-url))
    #"datomic:inf://([^/]*)/(.*)?" :>>
      (fn [[_ cluster-member-host _]] (str "datomic:inf://" cluster-member-host "/" dbname))
    #"datomic:dev://([^/]*)/(.*)?" :>>
      (fn [[_ transactor-host _]] (str "datomic:dev://" transactor-host "/" dbname))
    #"datomic:free://([^/]*)/(.*)?" :>>
      (fn [[_ transactor-host _]] (str "datomic:free://" transactor-host "/" dbname))
    #"datomic:mem://(.*)?" :>>
      (fn [[_ _]] (str "datomic:mem://" dbname))
    (throw (ex-info (str "Unknown Database URI form: " uri) {:uri uri}))))

(s/defn ^{:private true}
  biggest-ns-id :- long
  [db :- Db]
  (let [prefixes (q '[:find ?p :where [?n :k/prefix ?p]] db)
        ids (map #(Integer/parseInt (subs (first %) 2)) prefixes)]
    (if (seq ids) (apply max ids) 0)))

(s/defn get-prefix :- String
  "Get a new namespace prefix to use for a reference. If one does not exist, then generate a new one,
  and optimistically attempt to use it. The database function :atomic-check is used to check that the
  system table was not updated between scan and insertion."
  [sys :- Connection, ns-ref :- PartialUriString]
  (let [sysdb (rdb sys)
        known-prefix (if (t/prefix-style? ns-ref)
                       (subs ns-ref 0 (dec (count ns-ref)))
                       (ffirst (q '[:find ?p
                                    :in $ ?pref
                                    :where [?n :k/prefix ?p][?n :k/namespace ?pref]]
                                  sysdb (URI. ns-ref))))]
    (or known-prefix
        (loop [sdb sysdb, new-ns-id (inc (biggest-ns-id sysdb))]
          (let [new-ns (str "ns" new-ns-id)
                latest (d/t->tx (d/basis-t sdb))
                [result error] (try [@(d/transact sys [[:atomic-check latest]
                                                       {:db/id (Peer/tempid :k/system)
                                                        :k/prefix new-ns
                                                        :k/namespace (URI. ns-ref)}]) nil]
                                    (catch Throwable t [nil t]))]
            (if result
              new-ns
              (let [new-sysdb (rdb sys)]
                (recur new-sysdb (inc (biggest-ns-id new-sysdb))))))))))

(s/defn known-prefixes :- {String UriString}
  "Returns a map of known prefixes to namespace references."
  [sys :- Connection]
  (let [p (q '[:find ?p ?l :where [?n :k/prefix ?p] [?n :k/namespace ?l]] (rdb sys))]
    (reduce conj {} (map (fn [[p n]] [p (str n)]) p))))

(s/defn generate-graph-uri :- DatomicUrlString
  "Creates a new graph URI, based on the system graph and a graph name"
  [{sys :system, sysdb :system-db} :- Kiara, gn :- UriString]
  (let [[p-ref local] (qname/split-iri gn)
        prefix (get-prefix sys p-ref)
        db-name (str prefix "-" local)]
    (update-dbname sysdb db-name)))

(s/defn get-default :- DatomicUrlString
  "Gets the default graph given the system graph, or a default reference.
   Only uses the default if the system graph has not yet been established."
  [system :- Connection, dn :- DatomicUrlString]
  (let [sysdb (rdb system)
        est (try (str (ffirst (q '[:find ?dn :where [?dg :k/db-name ?dn][?sys :k/default ?dg]] sysdb)))
                 (catch Exception _))
        defgraphname (or est dn)]
    (d/create-database defgraphname)
    defgraphname))

(s/defn find-graph :- Connection
  "Gets a graph connection from a Kiara instance. Returns nil if the graph does not exist.
   Throws an exception if the graph is known, but cannot be found."
  [{:keys [system default]} :- Kiara, gn :- (s/maybe UriString)]
  (if (empty? gn)
    default
    (if-let [db-name (ffirst (q '[:find ?db :in $ ?gn :where [?g :k/db-name ?db] [?g :sd/name ?gn]]
                                (rdb system) (URI. gn)))]
      (or (d/connect (str db-name))
          (throw (ex-info "System identified graph is missing" {:graph gn :database db-name}))))))

(s/defn get-graph :- Connection
  "Gets a graph connection from a Kiara instance, creating a new one where necessary."
  [k :- Kiara, gn :- UriString]
  (let [sys (:system k)
        sysdb (rdb sys)
        gu (URI. gn)
        established (ffirst (q '[:find ?dn :in $ ?gn :where [?g :k/db-name ?dn][?g :sd/name ?gn]] sysdb gu))
        dbname (str (or established (generate-graph-uri k gn)))]
    (d/create-database dbname)
    (let [conn (d/connect dbname)]
      (when-not established
        @(d/transact conn cschema/system-partition)
        @(d/transact conn cschema/core-attributes)
        @(d/transact sys [{:db/id (Peer/tempid :k/system)
                           :sd/name gu
                           :k/db-name (URI. dbname)}]))
      conn)))

(s/defn ^{:private true}
  initial-system :- [{s/Keyword s/Any}]
  "Creates the initial data for the system graph"
  [sys-db :- DatomicUrlString
   sys-name :- UriString
   default-db :- DatomicUrlString
   default-name :- UriString]
  [{:db/id (Peer/tempid :k/system)
    :rdf/type :sd/NamedGraph
    :sd/name (URI. sys-name)
    :k/db-name (URI. sys-db)
    :k/namespaces [{:k/prefix "k" :k/namespace (URI. kiara-ns)}]
    :k/default {:db/id (Peer/tempid :k/system)
                :rdf/type :sd/NamedGraph
                :sd/name (URI. default-name)
                :k/db-name (URI. default-db)}}])

(def default-default-name "default")

(s/defn gname :- String
  "Gets the end of the path from a URL"
  [url :- DatomicUrlString]
  (last (str/split url #"/")))

(s/defn init :- Kiara
  "Creates a database and initializes it. Receives a connection parameter"
  ([sys-db :- DatomicUrlString]
     (init sys-db (update-dbname sys-db default-default-name)))
  ([sys-db :- DatomicUrlString, default-db :- DatomicUrlString]
     (let [sys-name (gname sys-db)
           default-name (gname default-db)
           created (d/create-database sys-db)
           created-default (d/create-database default-db)
           system-graph (d/connect sys-db)
           def-graph (d/connect default-db)]
       (when created
         @(d/transact system-graph cschema/system-partition)
         @(d/transact system-graph cschema/system-attributes)
         @(d/transact def-graph cschema/system-partition)
         @(d/transact def-graph cschema/core-attributes))
       (let [def-db (if created default-db (get-default system-graph default-db))
             k {:system system-graph
                :system-db sys-db
                :default def-graph
                :default-db def-db}]
         @(d/transact system-graph (initial-system sys-db sys-name default-db default-name))
         k))))

(def default-protocol "datomic:free")
(def default-host "localhost")
(def default-port 4334)
(def default-system "system")

(s/defn create :- Kiara
  "Set up a set of connections to databases that contain RDF graphs, starting with the system graph"
  ([] (create default-protocol default-host default-port default-system))
  ([root :- String] (create root default-system))
  ([root :- DatomicUrlString, system :- String]
     (init (str root (if-not (= \/ (last root)) "/") system)))
  ([protocol :- String, host :- String, port :- long, system :- String]
     (init (str protocol "://" host (if port (str ":" port)) "/" system))))

(s/defn infer-schema-to-conn :- Connection
  "Reads a TTL file and infers a schema that will load it. Adds this schema to the connection."
  [c :- Connection, file :- s/Any]
  (with-open [f (io/input-stream file)]
    (d/transact c (schema/datomic-schema f)))
  c)

(s/defn load-schema :- Kiara
  "Infers a schema from TTL data in a file, and loads it into a Kiara instance"
  ([k :- Kiara, file :- s/Any] (load-schema k file nil))
  ([k :- Kiara, file :- s/Any, graphname :- UriString?]
     (let [graph (if (empty? graphname) (:default k) (get-graph k graphname))]
     (with-open [i (io/input-stream file)]
       (let [schema (schema/datomic-schema i)]
         @(d/transact graph schema))))
   k))

(s/defn load-ttl :- Kiara
  "Load TTL data from a file into a Kiara instance."
  ([k :- Kiara, file :- s/Any] (load-ttl k file nil))
  ([k :- Kiara, file :- s/Any, graphname :- UriString?]
   (let [graph (if graphname (get-graph k graphname) (:default k))]
     (with-open [f (io/input-stream file)]
       (let [sys (:system k)
             prefix-gen-fn (fn [_ namesp-ref] (get-prefix sys namesp-ref))
             [data parser] (data/datomic-data (rdb graph) f (known-prefixes sys) prefix-gen-fn)]
         @(d/transact graph data))))
   k))

(s/defn get-triples :- [Triple]
  "Gets all triples from a graph"
  ([k :- Kiara] (get-triples k nil))
  ([k :- Kiara, graph :- (s/maybe UriString)]
     (if-let [g (find-graph k graph)]
       (let [gv (rdb g)
             raw (q '[:find ?sn ?p ?pn ?o
                      :where [?p :db/ident ?pn] [?p :k/rdf true] [?s ?p ?o] [?s :db/ident ?sn]] gv)
             obj-expand (fn [[s p pn o]]
                          (let [pe (d/entity gv p)]
                            (if (= :db.type/ref (:db/valueType pe))
                              [s pn (d/entity gv o)]
                              [s pn o])))]
         (map obj-expand raw)))))
