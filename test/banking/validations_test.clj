(ns banking.validations-test
  "TDD specs for the edge validations. These are pure predicates over the
   inputs alone -- they never look at account state, so they live at the edge
   and fail fast before any decision runs."
  (:require [clojure.test :refer [deftest testing is]]
            [banking.validations :as validations]))

(deftest valid-amount
  (testing "positive integers are valid"
    (is (validations/valid-amount? 1))
    (is (validations/valid-amount? 100)))
  (testing "zero is not a valid amount"
    (is (not (validations/valid-amount? 0))))
  (testing "negative amounts are invalid"
    (is (not (validations/valid-amount? -1)))
    (is (not (validations/valid-amount? -100))))
  (testing "non-integers are invalid"
    (is (not (validations/valid-amount? 10.5))))
  (testing "non-numbers are invalid"
    (is (not (validations/valid-amount? nil)))
    (is (not (validations/valid-amount? "10")))))

(deftest distinct-accounts
  (testing "different account ids are distinct"
    (is (validations/distinct-accounts? "acc-a" "acc-b")))
  (testing "the same account id is not distinct"
    (is (not (validations/distinct-accounts? "acc-a" "acc-a")))))
