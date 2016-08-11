(ns build.core
  (:require [planck.core :as core]
            [planck.io :as io]
            [planck.shell :as shell]
            [cljs.tools.reader :as reader]
            [clojure.string :as string]))

;; Helpers
(def make-dirs (partial shell/sh "mkdir" "-p"))
(defn copy [from to] (shell/sh "cp" "-r" from to))
(defn path-join [& args] (string/join "/" args))

(defn organize-sources
  "Given source folder and destination will go though all source files
  and put in a right folders dependending on a file namespace"
  [from to]
  (letfn [(is-ns? [form] (and (list? form) (= (first form) 'ns) (< 1 (count form))))
          (parse-ns [form] (cond (is-ns? form) (-> form second str)
                                 (list? form) (some parse-ns form)))
          (path-for-ns [ns] (-> ns (string/split ".") butlast (#(apply path-join to %))))
          (copy-source [source-file path-to]
            (let [source-path (.-path source-file)
                  source-name (-> source-path (string/split "/") last)]
              (make-dirs path-to)
              (copy source-path (path-join path-to source-name))))
          (find-out-path [file]
            (if (->> file :path (re-find #"clj$|cljs$|cljc$"))
              (->> file
                   core/slurp
                   (#(str "(\n" % "\n)"))
                   (reader/read-string {:read-cond :allow :features #{:clj :cljs}})
                   parse-ns
                   path-for-ns)
              (-> file
                  :path
                  (string/replace from "")
                  (string/split "/")
                  rest
                  butlast
                  ((fn[path-parts]
                     ;; HACK: If we would use clj_module(src=glob(['src/**/*'])) then Buck would copy
                     ;; everything under src folder, but root folder would be still src, same for tests.
                     ;; So here we just flatten folders together in order to avoid paths like module/src/src/file
                     (if (= (first path-parts)
                            (last (string/split to "/")))
                       (rest path-parts)
                       path-parts)))
                  (#(apply path-join to %)))))]
    (->> (core/file-seq from)
         (filter #(not (io/directory? %)))
         (mapv #(->> %
                     find-out-path
                     (copy-source %))))))

(defn organize-deps
  "Read deps file looking for sub-dependencies and merge all of them
  back into deps file"
  [deps-file]
  (letfn [(read-subdeps [path]
            (let [subdep-file (path-join path "deps")]
              (if (io/file-attributes subdep-file)
                (-> subdep-file core/slurp string/split-lines)
                [])))
          ]
    (->> (core/slurp deps-file)
         string/split-lines
         (map read-subdeps)
         (apply concat)
         distinct
         (string/join "\n")
         (core/spit deps-file))))

(defn merge-deps-src
  "Merge deps source into current module src folder"
  [deps-file to]
  (->> (core/slurp deps-file)
       string/split-lines
       (mapv #(copy (path-join % "src") to))))

(defn update-project-file
  "Updates project file and replace tokens there with supplied data"
  [name main path]
  (let [project-file (path-join path "project.clj")]
    (-> (core/slurp project-file)
        (string/replace "{{name}}" name)
        (string/replace "{{main}}" main)
        (string/replace "{{deps}}" (core/slurp (path-join path "deps")))
        (#(core/spit project-file %)))))

(defn ensure-main-exists
  "Creates entry point file which requires all the existing module
  namespaces (including tests) which simplifies REPL and testing. Used
  as main if no main was supplied"
  [main path type]
  (let [def-main "module.core"
        find-all-namespaces (fn[path]
                              (->> (shell/sh "find" path "-type" "f" "-name" "*.cljc" "-o" "-name" (str "*." type))
                                   :out
                                   string/split-lines
                                   (map #(-> %
                                             (string/replace path "")
                                             (string/replace "/" ".")
                                             (string/replace "_" "-")
                                             (string/split ".")
                                             butlast
                                             rest))
                                   (map #(string/join "." %))))
        main-file (fn[namespaces]
                    (str "(ns " def-main " (:require "
                         (string/join "\n" (map #(str "[" % "]") namespaces))
                         "))"))
        main-path (path-join path "src" "module")]
    (make-dirs main-path)
    (->> (concat (find-all-namespaces (path-join path "src"))
                 (find-all-namespaces (path-join path "test")))
         (filter (complement string/blank?))
         (filter (partial not= "deps"))
         main-file
         (core/spit (path-join main-path (str "core." type))))
    (if (string/blank? main)
      def-main
      main)))

(let [parse-args #(let [info-file (-> % first core/slurp string/trim)]
                    (zipmap [:name :type :main :src :out :task]
                            (conj (string/split info-file ";") (second %))))
      {:keys [src out type task name main]} (parse-args core/*command-line-args*)]
  (case task
    "build" (do
              (organize-sources src (path-join out "src"))
              (merge-deps-src (path-join out "deps") out)
              (organize-deps (path-join out "deps")))
    "test" (do
             (organize-sources src (path-join out "test"))
             (update-project-file name
                                  (ensure-main-exists main out type)
                                  out))))
