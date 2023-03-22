(ns taoensso.encore-tests
  (:require
   [clojure.test                     :as test :refer [deftest testing is]]
   ;; [clojure.test.check            :as tc]
   ;; [clojure.test.check.generators :as tc-gens]
   ;; [clojure.test.check.properties :as tc-props]
   [clojure.string  :as str]
   [taoensso.encore :as enc])

  #?(:cljs
     (:require-macros
      [taoensso.encore-tests
       :refer [test-macro-alias]])))

(comment
  (remove-ns      'taoensso.encore-tests)
  (test/run-tests 'taoensso.encore-tests))

;;;;

;; (deftest pass (is (= 1 1)))
;; (deftest fail (is (= 1 0)))

(defn- throw! [x] (throw (ex-info "Error" {:arg {:value x :type (type x)}})))

;;;;

(do
  (defn- test-fn "doc a" [x] x)
  (enc/defalias                 test-fn-alias-1 test-fn)
  (enc/defalias ^{:doc "doc b"} test-fn-alias-2 test-fn)
  (enc/defalias ^{:doc "doc b"} test-fn-alias-3 test-fn {:doc "doc c"})

  #?(:clj (defmacro ^:private test-macro [x] `~x))
  #?(:clj (enc/defalias test-macro-alias test-macro))

  #?(:cljs (def ^:private cljs-var "cljs-var-doc" "cljs-var-val"))
  #?(:cljs (enc/defalias  cljs-var-alias           cljs-var)))

(deftest _defalias
  ;; [1] v3.47.0+: Cljs aliases no longer copy metadata
  [        (is (=    (test-fn-alias-1 :x) :x))
   #?(:clj (is (= (-> test-fn-alias-1 var meta :doc) "doc a"))) ; [1]
           (is (= (-> test-fn-alias-2 var meta :doc) "doc b"))
           (is (= (-> test-fn-alias-3 var meta :doc) "doc c"))

   (is (= (test-macro-alias :x) :x))

      #?(:cljs (is (=     cljs-var-alias                "cljs-var-val")))
      #?(:cljs (is (= (-> cljs-var       var meta :doc) "cljs-var-doc")))
   ;; #?(:cljs (is (= (-> cljs-var-alias var meta :doc) "cljs-var-doc"))) ; [1]
   ])

(deftest _truss-invariants
  ;; Tested properly in Truss, just confirm successful imports here
  [(is (= (enc/have  string? "foo") "foo"))
   (is (= (enc/have! string? "foo") "foo"))
   (is (= (enc/have? string? "foo")  true))
   (is (= (enc/have?         "foo")  true))
   (is (enc/throws? (enc/have string? 5)))
   (is (enc/throws? :any
         {:data {:dynamic :dynamic-data
                 :arg     :arg-data}}
         (enc/with-truss-data :dynamic-data
           (enc/have? string? 5 :data :arg-data))))])

(deftest _submap?
  [(is (enc/submap? {:a {:b :B1 :c :C1}} {:a {:b :B1}}))
   (is (enc/submap? {:a {:b :B1       }} {:a {:c :submap/nx}}))])

(deftest _throws?
  (let [throw-common   (fn [] (throw (ex-info "Shenanigans" {:a :a1 :b :b1})))
        throw-uncommon (fn [] (throw #?(:clj (Error.) :cljs "Error")))]

    [(is      (enc/throws?                            (throw-common)))
     (is      (enc/throws? :common                    (throw-common)))
     (is      (enc/throws? :any                       (throw-common)))
     (is (not (enc/throws? :common                    (throw-uncommon))))
     (is      (enc/throws? :any                       (throw-uncommon)))

     (is      (enc/throws? :default #"Shenanigans"    (throw-common)))
     (is (not (enc/throws? :default #"Brouhaha"       (throw-common))))

     (is      (enc/throws? :default {:a :a1}          (throw-common)))
     (is (not (enc/throws? :default {:a :a1 :b :b2}   (throw-common))))

     (is      (enc/throws? :default {:a :a1} (throw (ex-info "Test" {:a :a1 :b :b1}))))
     (is (not (enc/throws? :default {:a :a1} (throw (ex-info "Test" {:a :a2 :b :b1})))))

     ;; Form must throw error, not return it
    #?(:clj
       [(is      (enc/throws? Exception (throw (Exception.))))
        (is (not (enc/throws? Exception        (Exception.))))]

       :cljs
       [(is      (enc/throws? js/Error (throw (js/Error.))))
        (is (not (enc/throws? js/Error        (js/Error.))))])]))

(deftest _catching-rf
  [(is (=   (reduce (enc/catching-rf            (fn [acc in] (conj acc         in)))  [] [:a :b]) [:a :b]))
   (is (=   (reduce (enc/catching-rf {:id :foo} (fn [acc in] (conj acc         in)))  [] [:a :b]) [:a :b]))
   (is (->> (reduce (enc/catching-rf {:id :foo} (fn [acc in] (conj acc (throw! in)))) [] [:a :b])
         (enc/throws? :common {:id :foo :call '(rf acc in) :args {:in {:value :a}}})))

   (is (=   (reduce-kv (enc/catching-rf (fn [acc k v] (assoc acc k         v)))  {} {:a :A}) {:a :A}))
   (is (->> (reduce-kv (enc/catching-rf (fn [acc k v] (assoc acc k (throw! v)))) {} {:a :A})
         (enc/throws? :common {:call '(rf acc k v) :args {:k {:value :a} :v {:value :A}}})))])

(deftest _catching-xform
  [(is (=   (transduce (enc/catching-xform (map identity)) (completing (fn [acc in] (conj acc in))) [] [:a :b]) [:a :b]))
   (is (->> (transduce (enc/catching-xform (map throw!))   (completing (fn [acc in] (conj acc in))) [] [:a :b])
         (enc/throws? :common {:call '(rf acc in) :args {:in {:value :a}}}))
     "Error in xform")

   (is (=   (transduce (enc/catching-xform (map identity)) (completing (fn [acc in] (conj acc         in)))  [] [:a :b]) [:a :b]))
   (is (->> (transduce (enc/catching-xform (map identity)) (completing (fn [acc in] (conj acc (throw! in)))) [] [:a :b])
         (enc/throws? :common {:call '(rf acc in) :args {:in {:value :a}}}))
     "Error in rf")])

#?(:clj
   (deftest _secure-rng-mock
     [(is (=
            (let [msrng (enc/secure-rng-mock!!! 5)] [(.nextLong msrng) (.nextDouble msrng)])
            (let [msrng (enc/secure-rng-mock!!! 5)] [(.nextLong msrng) (.nextDouble msrng)])))

      (is (not=
            (let [msrng (enc/secure-rng-mock!!! 5)] [(.nextLong msrng) (.nextDouble msrng)])
            (let [msrng (enc/secure-rng-mock!!! 2)] [(.nextLong msrng) (.nextDouble msrng)])))]))

#?(:clj
   (deftest _hex-strings
     [(is (= (enc/ba->hex-str (byte-array  0))    ""))
      (is (= (enc/ba->hex-str (byte-array [0]))   "00"))
      (is (= (enc/ba->hex-str (byte-array [0 1])) "0001"))
      (let [v (vec (range -128 128))]
        (is (= (-> v byte-array enc/ba->hex-str enc/hex-str->ba vec) v)))]))

#?(:clj
   (deftest _utf8-byte-strings
     (let [s "hello ಬಾ ಇಲ್ಲಿ ಸಂಭವಿಸ"]
       (is (= (-> s enc/str->utf8-ba enc/utf8-ba->str) s)))))

(deftest  _get-substr-by-idx
  [(is (= (enc/get-substr-by-idx nil            nil)         nil))
   (is (= (enc/get-substr-by-idx "123456789"    nil) "123456789"))
   (is (= (enc/get-substr-by-idx "123456789"      1)  "23456789"))
   (is (= (enc/get-substr-by-idx "123456789"     -3)       "789"))
   (is (= (enc/get-substr-by-idx "123456789"   -100) "123456789"))
   (is (= (enc/get-substr-by-idx "123456789"  0 100) "123456789"))
   (is (= (enc/get-substr-by-idx "123456789"  0   0)         nil))
   (is (= (enc/get-substr-by-idx "123456789"  0   1) "1"        ))
   (is (= (enc/get-substr-by-idx "123456789"  0  -1) "12345678" ))
   (is (= (enc/get-substr-by-idx "123456789"  0  -5) "1234"     ))
   (is (= (enc/get-substr-by-idx "123456789" -5  -3)      "56"  ))
   (is (= (enc/get-substr-by-idx "123456789"  4   3)         nil))])

(deftest  _get-substr-by-len
  [(is (= (enc/get-substr-by-len nil            nil)         nil))
   (is (= (enc/get-substr-by-len "123456789"    nil) "123456789"))
   (is (= (enc/get-substr-by-len "123456789"      1)  "23456789"))
   (is (= (enc/get-substr-by-len "123456789"     -3)       "789"))
   (is (= (enc/get-substr-by-len "123456789"   -100) "123456789"))
   (is (= (enc/get-substr-by-len "123456789"  0 100) "123456789"))
   (is (= (enc/get-substr-by-len "123456789"  0   0)         nil))
   (is (= (enc/get-substr-by-len "123456789"  0   1) "1"        ))
   (is (= (enc/get-substr-by-len "123456789"  0  -5)         nil))
   (is (= (enc/get-substr-by-len "123456789" -5   2)      "56"  ))])

(deftest _reduce-zip
  [(is (= (enc/reduce-zip assoc {}  [:a :b :c]     [1 2 3])   {:a 1, :b 2, :c 3}) "Vec,  normal")
   (is (= (enc/reduce-zip assoc {} '(:a :b :c)    '(1 2 3))   {:a 1, :b 2, :c 3}) "List, normal")
   (is (= (enc/reduce-zip assoc {}  [:a :b :c :a]  [1 2 3 4]) {:a 4, :b 2, :c 3}) "Vec,  replacing")
   (is (= (enc/reduce-zip assoc {} '(:a :b :c :a) '(1 2 3 4)) {:a 4, :b 2, :c 3}) "List, replacing")
   (is (= (enc/reduce-zip assoc {}  [:a :b :c]     [1 2])     {:a 1, :b 2})       "Vec,  uneven")
   (is (= (enc/reduce-zip assoc {} '(:a :b :c)    '(1 2))     {:a 1, :b 2})       "List, uneven")
   (is (= (enc/reduce-zip assoc {}  []             [1])       {})                 "Vec,  empty")
   (is (= (enc/reduce-zip assoc {} '()            '(1))       {})                 "List, empty")])

(deftest _select-nested-keys
  [(is (= (enc/select-nested-keys nil    nil)) {})
   (is (= (enc/select-nested-keys {:a 1} nil)  {}))
   (is (= (enc/select-nested-keys {    } [:a]) {}))

   (is (= (enc/select-nested-keys {:a 1 :b 1 :c 1} [:a :c]) {:a 1 :c 1}))
   (is (= (enc/select-nested-keys {:a 1 :b 1 :c {:ca 2 :cb 2 :cc {:cca 3 :ccb 3} :cd 2} :d 1}
            [:a :b {:c [:ca :cb {:cc [:cca]} :ce]
                    :d [:da :db]} {:e []}])

         {:a 1, :b 1, :c {:ca 2, :cb 2, :cc {:cca 3}}, :d 1}))])

(do
  (def ^:private cache-idx_ (atom 0))
  (enc/defn-cached ^:private cached-fn
    {:ttl-ms 1000 :size 2 :gc-every 100}
    "Example cached function"
    ([   ] (swap! cache-idx_ inc))
    ([_  ] (swap! cache-idx_ inc))
    ([_ _] (swap! cache-idx_ inc))))

(deftest _defn-cached
  [(testing "Basics"
     [(is (= (reset! cache-idx_ 0) 0))
      (is (= (cached-fn :mem/del :mem/all) nil))
      (is (= (cached-fn)     1))
      (is (= (cached-fn)     1))
      (is (= (cached-fn "a") 2))
      (is (= (cached-fn "a") 2))
      (is (= (cached-fn "b") 3))
      (is (= (cached-fn "b") 3))
      (is (= (cached-fn "a" "b")) 4)
      (is (= (cached-fn "a" "b")) 4)
      (is (= (cached-fn "a") 2))

      (is (= (cached-fn :mem/fresh)     5))
      (is (= (cached-fn :mem/fresh)     6))
      (is (= (cached-fn)                6))
      (is (= (cached-fn "a")            2))
      (is (= (cached-fn :mem/fresh "a") 7))

      (is (= (cached-fn "b")            3))
      (is (= @cache-idx_                7))
      (is (= (cached-fn :mem/del "b") nil))
      (is (= @cache-idx_                7))
      (is (= (cached-fn "b")            8))

      (is (= (cached-fn "a"                  7)))
      (is (= (cached-fn :mem/del :mem/all) nil))
      (is (= (cached-fn "a"                  8)))])

   #?(:clj
      (testing "TTL"
        [(is (= (reset! cache-idx_ 0)           0))
         (is (= (cached-fn :mem/del :mem/all) nil))

         (is (= (cached-fn "foo") 1))
         (is (= (cached-fn "foo") 1))

         (do (Thread/sleep 1200) :sleep>ttl-ms)
         (is (= (cached-fn "foo") 2))
         (is (= (cached-fn "foo") 2))]))

   (testing "Max size"
     [(is (= (reset! cache-idx_ 0)           0))
      (is (= (cached-fn :mem/del :mem/all) nil))

      (is (= (cached-fn "infrequent-old")    1))
      (is (= (cached-fn "infrequent-recent") 2))
      (is (= (cached-fn "frequent")          3))

      (do (dotimes [_ 100] (cached-fn "frequent")) :call>gc-every)

      (is (= (cached-fn "frequent")          3) "Cache retained (freq & recent)")
      (is (= (cached-fn "infrequent-recent") 2) "Cache retained (recent)")
      (is (= (cached-fn "infrequent-old")    4) "Cache dropped")])])

(deftest _rolling-sequentials
  [(do     (is (= (let [rv (enc/rolling-vector 3)] (dotimes [idx 1e4] (rv idx))      (rv))  [9997 9998 9999])))
   #?(:clj (is (= (let [rl (enc/rolling-list   3)] (dotimes [idx 1e4] (rl idx)) (vec (rl))) [9997 9998 9999])))])

(deftest _counters
  (let [c (enc/counter)]
    [(is (= @c        0))
     (is (= (c)       0))
     (is (= @c        1))
     (is (= (c 5)     1))
     (is (= @c        6))
     (is (= (c :+= 2) 8))
     (is (= (c :=+ 2) 8))
     (is (= @c        10))]))

#?(:clj
   (deftest _precache
     (let [c   (enc/counter)
           pcf (enc/pre-cache 3 1 (fn inc-counter [] (c)))]

       [(do (Thread/sleep 1000) "Sleep for cache to initialize (async)")
        (is (= @c 3)  "f was run to initialize cache")
        (is (= (pcf) 0))
        (do (Thread/sleep 1000) "Sleep for cache to replenish (async)")
        (is (= @c 4) "f was run to replenish cache")
        (is (= (pcf) 1))
        (is (= (pcf) 2))
        (is (= (pcf) 3))])))

;;;;

#?(:cljs (test/run-tests))
