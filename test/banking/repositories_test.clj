(ns banking.repositories-test
  "TDD specs for the imperative shell. `commit` is the only edge that mutates:
   given a reference (an atom holding the repository) and a pure decision, it

     1. reads the current repository from the reference,
     2. runs (decision repository & args),
     3. on failure returns the failure map and touches nothing,
     4. on success publishes the new repository to the reference (atomically),
        runs each effect, and returns the decision's :value.

   The reference is passed to `commit` explicitly (rather than a namespace-level
   atom) so these tests stay isolated and order-independent.

   Every operation draws its timestamp from `tick`, so no two operations share
   a timestamp anywhere in the suite."
  (:require [clojure.test :refer [deftest testing is]]
            [matcher-combinators.test :refer [match?]]
            [banking.fixtures :refer [tick]]
            [banking.repositories :as repositories]
            [banking.accounts :as accounts]))

(deftest commit-returns-the-decision-value
  (testing "success yields the decision's :value, not the whole success map"
    (let [ref (atom {})]
      (is (= "acc-1" (repositories/commit ref accounts/create-account "acc-1" (tick))))
      (is (= 100 (repositories/commit ref accounts/deposit "acc-1" 100 (tick)))))))

(deftest state-is-visible-to-the-next-operation
  (testing "a committed operation is visible to the next one"
    (let [ref (atom {})]
      (repositories/commit ref accounts/create-account "acc-1" (tick))
      (repositories/commit ref accounts/deposit "acc-1" 100 (tick))
      (repositories/commit ref accounts/deposit "acc-1" 25 (tick))
      (is (= 125 (:balance (get @ref "acc-1")))))))

(deftest failure-preserves-stored-state
  (testing "a failed operation leaves the stored state exactly as it was"
    (let [ref (atom {})]
      (repositories/commit ref accounts/create-account "acc-1" (tick))
      (repositories/commit ref accounts/deposit "acc-1" 100 (tick))
      (let [before @ref
            result (repositories/commit ref accounts/deposit "ghost" 50 (tick))]
        (is (match? {:error :account-not-found} result))
        (is (= before @ref))))))
