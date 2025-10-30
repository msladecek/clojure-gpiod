(ns com.msladecek.gpio.libgpiod-cli-adapter
  "Adapters for `libgpiod` command line tools.

  See https://libgpiod.readthedocs.io/en/latest/gpio_tools.html for details.
  "
  (:require
   [clojure.instant :refer [read-instant-date]]
   [clojure.string :as string]
   [clojure.edn :as edn]
   [babashka.process :refer [process]]
   [instaparse.core :as insta]))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(insta/defparser ^:no-doc parser--get
  "pairs = pair*
pair = <'\"'> line <'\"='> value <#'[ \t\n]*'>
<line> = #'[A-Z0-9]+'
value = #'(active|inactive)'")

#_{:clj-kondo/ignore [:unresolved-symbol]}
(insta/defparser ^:no-doc parser--detect
  "rows = row*
  row = chip <' '> id <' ('> line-count <' lines)\n'>
  <chip> = #'\\S+'
  id = #'\\S+'
  line-count = #'[0-9]+'")

#_{:clj-kondo/ignore [:unresolved-symbol]}
(insta/defparser ^:no-doc parser--info
  "sections = section*
  section = header line*
  <header> = chip <#' - .*\n'>
  <chip> = #'\\S+'
  line = <#'\\s+line\\s+'> line-no <#':\\s+'> label <#'\\s+'> line-type (<' consumer='> consumer)? <'\n'>
  line-no = #'[0-9]+'
  label = #'\\S+'
  line-type = #'(input|output)'
  consumer = #'\\S+'")

(defn detect
  "Adapter for `gpiodetect`.

  See https://libgpiod.readthedocs.io/en/latest/gpiodetect.html for more details.
  "
  []
  (let [result @(process {} "gpiodetect")]
    (if-not (zero? (:exit result))
      (throw (ex-info "subprocess failed"
               {:command ["gpiodetect"]
                :error (slurp (:err result))}))
      (->> (:out result)
        (slurp)
        (insta/parse parser--detect)
        (insta/transform {:line-count edn/read-string
                          :id #(subs % 1 (dec (count %)))
                          :row (fn [chip id line-count]
                                 {:chip chip
                                  :id id
                                  :line-count line-count})
                          :rows vector})))))

(defn info
  "Adapter for `gpioinfo`.

  See https://libgpiod.readthedocs.io/en/latest/gpioinfo.html for more details.
  "
  []
  (let [result @(process {} "gpioinfo")]
    (if-not (zero? (:exit result))
      (throw (ex-info "subprocess failed"
               {:command ["gpioinfo"]
                :error (slurp (:err result))}))
      (->> (:out result)
        (slurp)
        (insta/parse parser--info)
        (insta/transform {:line-no edn/read-string
                          :line-type keyword
                          :label #(if (= \" (first %))
                                    (subs % 1 (dec (count %)))
                                    %)
                          :consumer #(subs % 1 (dec (count %)))
                          :line (fn transform-line
                                  ([line-no label line-type]
                                   (transform-line line-no label line-type nil))
                                  ([line-no label line-type consumer]
                                   (cond-> {:line-no line-no
                                            :label label
                                            :type line-type}
                                     consumer (assoc :consumer consumer))))
                          :section (fn [chip & lines]
                                     (->> lines
                                       (mapv #(assoc % :chip chip))))
                          :sections (fn [& sections]
                                      (->> sections
                                        (apply concat)
                                        (into [])))})))))

(defn ^:no-doc opts->cmdline-opts [opts]
  (->> opts
    (map (fn [[opt value]]
           (if (= true value)
             (str "--" (name opt))
             (str "--" (name opt) "=" value))))))

(defn get-lines
  "Adapter for `gpioget`.

  Option `--as-is` is used by default to avoid resetting the lines.
  You may override it with `{:as-is false}`.

  See https://libgpiod.readthedocs.io/en/latest/gpioget.html for more details.
  "
  ([lines]
   (get-lines {:as-is true} lines))
  ([opts lines]
   (let [cmdline-opts (opts->cmdline-opts opts)
         command (into ["gpioget"] (concat cmdline-opts lines))
         result @(apply process {} command)]
     (if-not (zero? (:exit result))
       (throw (ex-info "subprocess failed"
                {:command command
                 :error (slurp (:err result))}))
       (->> (:out result)
         (slurp)
         (insta/parse parser--get)
         (insta/transform {:value {"inactive" false
                                   "active" true}
                           :pair vector
                           :pairs (fn [& pairs] (into {} pairs))}))))))

(defn set-lines
  "Adapter for `gpioset`.

  See https://libgpiod.readthedocs.io/en/latest/gpioset.html for more details.
  "
  ([lines-and-values]
   (set-lines {} lines-and-values))
  ([opts lines-and-values]
   (let [cmdline-opts (opts->cmdline-opts opts)
         command (->> lines-and-values
                   (map (fn [[line value]]
                          (str line "=" (if value "active" "inactive"))))
                   (concat cmdline-opts)
                   (into ["gpioset"]))
         result @(apply process {} command)]
     (when-not (zero? (:exit result))
       (throw (ex-info "subprocess failed"
                {:command command
                 :error (slurp (:err result))}))))))

(defn set-lines-once
  "Same as set-lines but exit imediately after applying the new values."
  [lines-and-values]
  (set-lines {:toggle "0s"} lines-and-values))

(defn ^:no-doc -trim-edge-event [message]
  (first (string/split message #"\n")))

(defn ^:no-doc -parse-edge-event [message {:keys [use-default-format parse-timestamp]}]
  (if-not use-default-format
    {:message (-trim-edge-event message)}
    (let [message (-trim-edge-event message)
          [timestamp-str chip line event-type-str] (string/split message #" ")]
      {:timestamp (if parse-timestamp (read-instant-date timestamp-str) timestamp-str)
       :line line
       :chip chip
       :event-type (keyword event-type-str)
       :message message})))

(defn monitor-lines
  "Adapter for `gpiomon`.

  Optional arguments are passed along as if they were long command line options.
  By default, the `callback` function will receive a map containing timestamp, line, chip,
  event-type (:rising or :falling) and the raw message received on stdout.
  If the event-clock option is specified, the timestamp will not be parsed, it will be a string.
  If a custom output format is specified, the map will contain only the raw message.

  See https://libgpiod.readthedocs.io/en/latest/gpiomon.html for more details.
  "
  ([callback lines]
   (monitor-lines {} callback lines))
  ([opts callback lines]
   (let [cmdline-opts (opts->cmdline-opts
                        (merge
                          {:format "%U %c %l %E"
                           :event-clock "realtime"}
                          opts))
         command (into ["gpiomon"] (concat cmdline-opts lines))
         proc (apply process command)
         stdout (:out proc)
         parsing-callback (fn [message]
                            (callback (-parse-edge-event message
                                        {:use-default-format (nil? (:format opts))
                                         :parse-timestamp (nil? (:event-clock opts))})))
         reader-thread (Thread/new
                         (fn []
                           (loop []
                             (let [buffer (byte-array 60)]
                               (let [read-result (.read stdout buffer)]
                                 (when (= -1 read-result)
                                   (throw (ex-info "error reading output stream"
                                            {:command command}))))
                               (parsing-callback (String/new buffer))
                               (recur)))))]
     (.start reader-thread)
     @proc)))

#_:clj-kondo/ignore
(defn notify-lines
  "Adapter for `gpionotify`.

  See https://libgpiod.readthedocs.io/en/latest/gpionotify.html for more details.
  "
  ([callback lines]
   (notify-lines {} callback lines))
  ([opts callback lines]
   (throw (ex-info "not implemented" {}))))
