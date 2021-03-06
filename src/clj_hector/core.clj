(ns clj-hector.core
  (:import [java.io Closeable]
           [me.prettyprint.hector.api Cluster]
           [me.prettyprint.hector.api.factory HFactory]
           [me.prettyprint.cassandra.service CassandraHostConfigurator]
           [me.prettyprint.cassandra.serializers StringSerializer]
           [me.prettyprint.cassandra.model QueryResultImpl HColumnImpl ColumnSliceImpl RowImpl RowsImpl]))

;; work in progress; following through sample usages on hector wiki
;; https://github.com/rantav/hector/wiki/User-Guide

(defn closeable-cluster
  [cluster]
  (proxy [Cluster Closeable] []
    (close []
           (.. cluster getConnectionManager shutdown))))

(defn cluster
  "Connects to Cassandra cluster"
  ([cluster-name host]
     (cluster cluster-name host 9160))
  ([cluster-name host port]
     (HFactory/getOrCreateCluster cluster-name
                                  (CassandraHostConfigurator. (str host ":" port)))))
(defn keyspace
  [cluster name]
  (HFactory/createKeyspace name cluster))

(defprotocol ToClojure
  (to-clojure [x] "Convert hector types to Clojure data structures"))

(extend-protocol ToClojure
  RowsImpl
  (to-clojure [s]
              (map to-clojure (iterator-seq (.iterator s))))
  RowImpl
  (to-clojure [s]
              {:key (.getKey s)
               :columns (to-clojure (.getColumnSlice s))})
  
  ColumnSliceImpl
  (to-clojure [s]
              (into {}
                    (for [c (.getColumns s)]
                      (to-clojure c))))
  HColumnImpl
  (to-clojure [s]
              {(.getName s) (.getValue s)})

  QueryResultImpl
  (to-clojure [s]
              (with-meta (to-clojure (.get s)) {:exec_us (.getExecutionTimeMicro s)
                                                :host (.getHostUsed s)})))


(def *string-serializer* (StringSerializer/get))

(defn put-row
  "Stores values in columns in map m against row key pk"
  [ks cf pk m]
  (let [mut (HFactory/createMutator ks *string-serializer*)]
    (if (= 1 (count (keys m)))
      (.insert mut pk cf (HFactory/createStringColumn (first (keys m))
                                                      (first (vals m))))
      (do (doseq [kv m]
            (.addInsertion mut pk cf (HFactory/createStringColumn (first kv)
                                                                  (last kv))))
          (.execute mut)))))

(defn get-rows
  "In keyspace ks, retrieve rows for pks within column family cf"
  [ks cf pks]
  (to-clojure (.. (doto (HFactory/createMultigetSliceQuery ks
                                                           *string-serializer*
                                                           *string-serializer*
                                                           *string-serializer*)
                    (.setColumnFamily cf)
                    (. setKeys (object-array pks))
                    (.setRange "" "" false Integer/MAX_VALUE))
                  execute)))

(defn delete-columns
  [ks cf pk cs]
  (let [mut (HFactory/createMutator ks *string-serializer*)]
    (doseq [c cs] (.addDeletion mut pk cf c *string-serializer*))
    (.execute mut)))

(defn get-columns
  "In keyspace ks, retrieve c columns for row pk from column family cf"
  [ks cf pk c]
  (if (< 2 (count c))
    (to-clojure (.. (doto (HFactory/createStringColumnQuery ks)
                      (.setColumnFamily cf)
                      (.setKey pk)
                      (.setName c))
                    execute))
    (to-clojure (.. (doto (HFactory/createSliceQuery ks
                                                     *string-serializer*
                                                     *string-serializer*
                                                     *string-serializer*)
                      (.setColumnFamily cf)
                      (.setKey pk)
                      (. setColumnNames (object-array c)))
                    execute))))

