(ns banking.repositories-test
  "TDD specs for the imperative shell. The repository knows nothing about the
   account operations: you read the current ledger, manipulate it freely with
   the operations from `banking.accounts`, and only when you `commit` the result
   does the bank change.

   These specs pin down that contract:

     - manipulations do not touch the bank until commit;
     - committing reflects the whole (manipulated) ledger, and the bank stays
       consistent afterwards;
     - if the manipulation ended in a failure, commit reflects nothing (so a
       failure anywhere means the earlier steps never reach the bank);
     - a result with no :persist effect is not reflected.

   The bank is a namespace-level atom, so each test resets it first. Every
   operation draws its timestamp from `tick`, so no two operations share one."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [banking.fixtures :refer [tick]]
            [banking.repositories :as repositories]
            [banking.accounts :as accounts]))

(use-fixtures :each (fn [run] (repositories/reset-bank!) (run)))

(deftest a-manipulation-reaches-the-bank-only-on-commit
  (testing "the ledger is manipulated freely, but the bank changes only at commit"
    (let [r1 (accounts/create-account (repositories/ledger) "acc-1" (tick))
          r2 (accounts/deposit (:ledger r1) "acc-1" 100 (tick))
          r3 (accounts/deposit (:ledger r2) "acc-1" 25 (tick))]
      ;; three operations happened, but nothing has touched the bank yet
      (is (= {} (repositories/ledger)))
      ;; commit reflects the whole manipulated ledger and returns the value
      (is (= 125 (repositories/commit r3)))
      (let [account (get (repositories/ledger) "acc-1")]
        (is (= 125 (:balance account)))
        (is (= 125 (:inflow account)))
        (is (= 2 (count (:transactions account))))))))

(deftest a-failure-anywhere-persists-nothing
  (testing "if a later step fails, the earlier successful ones never reach the bank"
    (let [r1 (accounts/create-account (repositories/ledger) "acc-1" (tick))
          r2 (accounts/deposit (:ledger r1) "acc-1" 100 (tick))
          r3 (accounts/deposit (:ledger r2) "ghost" 50 (tick))]
      (is (match? {:error :account-not-found} (repositories/commit r3)))
      (is (= {} (repositories/ledger))))))

(deftest committing-without-a-persist-effect-changes-nothing
  (testing "a result carrying no :persist effect is not reflected into the bank"
    (let [no-persist {:ledger {"acc-1" :tampered} :value :ok :effects []}]
      (is (= :ok (repositories/commit no-persist)))
      (is (= {} (repositories/ledger))))))
