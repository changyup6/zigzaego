import math

from weglee_ai.metrics import bias, wape


def test_wape_hand_calc():
    # |10-8| + |20-25| = 7, 합 30
    assert wape([10, 20], [8, 25]) == 7 / 30


def test_wape_perfect_is_zero():
    assert wape([3, 4], [3, 4]) == 0


def test_bias_sign():
    assert bias([10, 10], [12, 12]) > 0
    assert bias([10, 10], [8, 8]) < 0


def test_zero_actual_is_nan():
    assert math.isnan(wape([0, 0], [1, 1]))
