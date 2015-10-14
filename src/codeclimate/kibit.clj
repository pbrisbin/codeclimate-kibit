(ns codeclimate.kibit
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.tools.cli :as cli]
            [kibit.driver :as kibit]
            [kibit.reporters :as reporters]
            [cheshire.core :as json])
  (:import (java.io StringWriter File))
  (:gen-class))

(defn pprint-code [form]
  (let [string-writer (StringWriter.)]
    (pp/write form
              :dispatch pp/code-dispatch
              :stream string-writer
              :pretty true)
    (str string-writer)))

(defn codeclimate-reporter
  [check-map]
  (let [{:keys [file line expr alt]} check-map
        issue {:type               "issue"
               :check_name         "kibit/suggestion"
               :description        (str "Non-idiomatic code found in `" (first (seq expr)) "`")
               :categories         ["Clarity" "Style"]
               :location           {:path  (subs (str file) 2)
                                    :lines {:begin line
                                            :end   line}}
               :content            {:body (str "Consider using:\n"
                                        "```clojure\n"
                                        (pprint-code alt) "\n"
                                        "```\n"
                                        "instead of:\n"
                                        "```clojure\n"
                                        (pprint-code expr) "\n"
                                        "```")}
               :remediation_points 50000}]
    (println (str (json/generate-string issue) "\0"))))

(defn exclude? [excluded-paths candidate]
  (some #(.startsWith candidate %) excluded-paths))

(defn target-files
  [dir config]
  (let [excluded (map #(str (io/file dir %)) (:exclude_paths config))]
    (->> (file-seq dir)
         (filter #(.isFile ^File %))
         (remove #(exclude? excluded (str ^File %))))))

(defn analize
  [dir config]
  (let [reporter-name "codeclimate"
        reporters-map (assoc reporters/name-to-reporter reporter-name
                                                        codeclimate-reporter)
        target-files  (target-files dir config)]
    (with-redefs [reporters/name-to-reporter reporters-map]
      (doall (kibit/run target-files "--reporter" reporter-name)))))

(def cli-options
  [["-C" "--config PATH" "Load PATH as a config file"]
   ["-h" "--help"]])

(defn usage [options-summary]
  (->> ["CodeClimate kibit engine"
        ""
        "Usage: java -jar codeclimate.jar [options] DIR"
        ""
        "Options:"
        options-summary
        ""]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit [status message]
  (println message)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (:help options) (exit 0 (usage summary))
      (not= (count arguments) 1) (exit 1 (usage summary))
      errors (exit 0 (error-msg errors)))
    (let [target-dir  (io/file (first arguments))
          config-file (io/file (:config options))
          config-data (when (and config-file (.exists config-file))
                        (json/parse-string (slurp config-file)))]
      (analize target-dir config-data))))
