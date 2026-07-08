(ns banking.fixtures
  "Shared test fixtures. `tick` is a monotonic clock that hands out strictly
   increasing timestamps starting at 1000, so every operation across every test
   gets its own value and no timestamp is ever reused.")

(def ^:private counter (atom 999))

(defn tick
  "Return the next timestamp, one greater than the previous (the first is 1000)."
  []
  (swap! counter inc))
