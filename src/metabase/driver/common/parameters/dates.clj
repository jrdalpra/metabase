(ns metabase.driver.common.parameters.dates
  "Shared code for handling datetime parameters, used by both MBQL and native params implementations."
  (:require [java-time :as t]
            [medley.core :as m]
            [metabase.mbql.schema :as mbql.s]
            [metabase.models.params :as params]
            [metabase.util
             [date-2 :as u.date]
             [schema :as su]]
            [schema.core :as s]))

(s/defn date-type?
  "Is param type `:date` or some subtype like `:date/month-year`?"
  [param-type :- s/Keyword]
  (or (= param-type :date)
      (= "date" (namespace param-type))))

;; Both in MBQL and SQL parameter substitution a field value is compared to a date range, either relative or absolute.
;; Currently the field value is casted to a day (ignoring the time of day), so the ranges should have the same
;; granularity level.
;;
;; See https://github.com/metabase/metabase/pull/4607#issuecomment-290884313 how we could support
;; hour/minute granularity in field parameter queries.

(defn- day-range
  [start end]
  {:start start, :end end})

(defn- comparison-range
  ([t unit]
   (comparison-range t t unit))

  ([start end unit]
   (merge
    (u.date/comparison-range start unit :>= {:resolution :day})
    (u.date/comparison-range end   unit :<= {:resolution :day, :end :inclusive}))))

(defn- week-range [start end]
  (comparison-range start end :week))

(defn- month-range [start end]
  (comparison-range start end :month))

(defn- year-range [start end]
  (comparison-range start end :year))

(defn- quarter-range
  [quarter year]
  (let [year-quarter (t/year-quarter year (case quarter
                                            "Q1" 1
                                            "Q2" 2
                                            "Q3" 3
                                            "Q4" 4))]
    {:start (.atDay year-quarter 1)
     :end   (.atEndOfQuarter year-quarter)}))

(def ^:private operations-by-date-unit
  {"day"   {:unit-range day-range
            :to-period  t/days}
   "week"  {:unit-range week-range
            :to-period  t/weeks}
   "month" {:unit-range month-range
            :to-period  t/months}
   "year"  {:unit-range year-range
            :to-period  t/years}})


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              DATE STRING DECODERS                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

;; For parsing date strings and producing either a date range (for raw SQL parameter substitution) or a MBQL clause

(defn- expand-parser-groups
  [group-label group-value]
  (case group-label
    :unit (conj (seq (get operations-by-date-unit group-value))
                [group-label group-value])
    :int-value [[group-label (Integer/parseInt group-value)]]
    (:date :date-1 :date-2) [[group-label (u.date/parse group-value)]]
    [[group-label group-value]]))


(defn- regex->parser
  "Takes a regex and labels matching the regex capturing groups. Returns a parser which takes a parameter value,
  validates the value against regex and gives a map of labels and group values. Respects the following special label
  names:

      :unit – finds a matching date unit and merges date unit operations to the result
      :int-value – converts the group value to integer
      :date, :date1, date2 – converts the group value to absolute date"
  [regex group-labels]
  (fn [param-value]
    (when-let [regex-result (re-matches regex param-value)]
      (into {} (mapcat expand-parser-groups group-labels (rest regex-result))))))

;; Decorders consist of:
;; 1) Parser which tries to parse the date parameter string
;; 2) Range decoder which takes the parser output and produces a date range relative to the given datetime
;; 3) Filter decoder which takes the parser output and produces a mbql clause for a given mbql field reference

(def ^:private relative-date-string-decoders
  [{:parser #(= % "today")
    :range  (fn [_ dt]
              {:start dt,
               :end   dt})
    :filter (fn [_ field] [:= [:datetime-field field :day] [:relative-datetime :current]])}

   {:parser #(= % "yesterday")
    :range  (fn [_ dt]
              {:start (t/minus dt (t/days 1))
               :end   (t/minus dt (t/days 1))})
    :filter (fn [_ field] [:= [:datetime-field field :day] [:relative-datetime -1 :day]])}

   ;; adding a tilde (~) at the end of a past<n><unit> filter means we should include the current day/etc.
   ;; e.g. past30days  = past 30 days, not including partial data for today ({:include-current false})
   ;;      past30days~ = past 30 days, *including* partial data for today   ({:include-current true})
   {:parser (regex->parser #"past([0-9]+)(day|week|month|year)s(~?)", [:int-value :unit :include-current?])
    :range  (fn [{:keys [unit int-value unit-range to-period include-current?]} dt]
              (unit-range (t/minus dt (to-period int-value))
                          (t/minus dt (to-period (if (seq include-current?) 0 1)))))
    :filter (fn [{:keys [unit int-value include-current?]} field]
              [:time-interval field (- int-value) (keyword unit) {:include-current (boolean (seq include-current?))}])}

   {:parser (regex->parser #"next([0-9]+)(day|week|month|year)s(~?)" [:int-value :unit :include-current?])
    :range  (fn [{:keys [unit int-value unit-range to-period include-current?]} dt]
              (unit-range (t/plus dt (to-period (if (seq include-current?) 0 1)))
                          (t/plus dt (to-period int-value))))
    :filter (fn [{:keys [unit int-value]} field]
              [:time-interval field int-value (keyword unit)])}

   {:parser (regex->parser #"last(day|week|month|year)" [:unit])
    :range  (fn [{:keys [unit-range to-period]} dt]
              (let [last-unit (t/minus dt (to-period 1))]
                (unit-range last-unit last-unit)))
    :filter (fn [{:keys [unit]} field]
              [:time-interval field :last (keyword unit)])}

   {:parser (regex->parser #"this(day|week|month|year)" [:unit])
    :range  (fn [{:keys [unit-range]} dt]
              (unit-range dt dt))
    :filter (fn [{:keys [unit]} field]
              [:time-interval field :current (keyword unit)])}])

(defn- ->iso-8601-date [t]
  (t/format :iso-local-date t))

;; TODO - using `range->filter` so much below seems silly. Why can't we just bucket the field and use `:=` clauses?
(defn- range->filter
  [{:keys [start end]} field]
  [:between [:datetime-field field :day] (->iso-8601-date start) (->iso-8601-date end)])

(def ^:private absolute-date-string-decoders
  ;; year and month
  [{:parser (regex->parser #"([0-9]{4}-[0-9]{2})" [:date])
    :range  (fn [{:keys [date]} _]
              (month-range date date))
    :filter (fn [{:keys [date]} field-id-clause]
              (range->filter (month-range date date) field-id-clause))}
   ;; quarter year
   {:parser (regex->parser #"(Q[1-4]{1})-([0-9]{4})" [:quarter :year])
    :range  (fn [{:keys [quarter year]} _]
              (quarter-range quarter (Integer/parseInt year)))
    :filter (fn [{:keys [quarter year]} field-id-clause]
              (range->filter (quarter-range quarter (Integer/parseInt year))
                             field-id-clause))}
   ;; single day
   {:parser (regex->parser #"([0-9-T:]+)" [:date])
    :range  (fn [{:keys [date]} _]
              {:start date, :end date})
    :filter (fn [{:keys [date]} field-id-clause]
              (let [iso8601date (->iso-8601-date date)]
                [:= [:datetime-field field-id-clause :day] iso8601date]))}
   ;; day range
   {:parser (regex->parser #"([0-9-T:]+)~([0-9-T:]+)" [:date-1 :date-2])
    :range  (fn [{:keys [date-1 date-2]} _]
              {:start date-1, :end date-2})
    :filter (fn [{:keys [date-1 date-2]} field-id-clause]
              [:between [:datetime-field field-id-clause :day] (->iso-8601-date date-1) (->iso-8601-date date-2)])}
   ;; before day
   {:parser (regex->parser #"~([0-9-T:]+)" [:date])
    :range  (fn [{:keys [date]} _]
              {:end date})
    :filter (fn [{:keys [date]} field-id-clause]
              [:< [:datetime-field field-id-clause :day] (->iso-8601-date date)])}
   ;; after day
   {:parser (regex->parser #"([0-9-T:]+)~" [:date])
    :range  (fn [{:keys [date]} _]
              {:start date})
    :filter (fn [{:keys [date]} field-id-clause]
              [:> [:datetime-field field-id-clause :day] (->iso-8601-date date)])}])

(def ^:private all-date-string-decoders
  (concat relative-date-string-decoders absolute-date-string-decoders))

(defn- execute-decoders
  "Returns the first successfully decoded value, run through both parser and a range/filter decoder depending on
  `decoder-type`."
  [decoders decoder-type decoder-param date-string]
  (some (fn [{parser :parser, parser-result-decoder decoder-type}]
          (when-let [parser-result (parser date-string)]
            (parser-result-decoder parser-result decoder-param)))
        decoders))

(defn date-string->range
  "Takes a string description of a date range such as 'lastmonth' or '2016-07-15~2016-08-6' and return a MAP with
  `:start` and `:end` as iso8601 string formatted dates, respecting the given timezone."
  [date-string report-timezone-id]
  (let [#_formatter-local-tz #_ (u.date/parse % report-timezone-id)
        #_formatter-no-tz    #_ (tf/formatter "yyyy-MM-dd")
        today                (t/local-date)]
    ;; Relative dates respect the given time zone because a notion like "last 7 days" might mean a different range of
    ;; days depending on the user timezone
    (or (->> (execute-decoders relative-date-string-decoders :range today date-string)
             (m/map-vals u.date/format #_(partial tf/unparse formatter-local-tz)))
        ;; Absolute date ranges don't need the time zone conversion because in SQL the date ranges are compared
        ;; against the db field value that is casted granularity level of a day in the db time zone
        (->> (execute-decoders absolute-date-string-decoders :range nil date-string)
             (m/map-vals u.date/format #_(partial tf/unparse formatter-no-tz))))))

(s/defn date-string->filter :- mbql.s/Filter
  "Takes a string description of a *date* (not datetime) range such as 'lastmonth' or '2016-07-15~2016-08-6' and
   returns a corresponding MBQL filter clause for a given field reference."
  [date-string :- s/Str, field :- (s/cond-pre su/IntGreaterThanZero mbql.s/Field)]
  (execute-decoders all-date-string-decoders :filter (params/wrap-field-id-if-needed field) date-string))
