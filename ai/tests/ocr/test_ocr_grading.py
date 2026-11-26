from weglee_ai.ocr.grading import match_lines, score_doc, summarize


def _doc(*rows, handwritten=False):
    return {"items": [{"name": n, "qty": q, "price": p} for n, q, p in rows], "handwritten": handwritten}


GOLD = _doc(("베이컨 1kg", 3, 11500), ("파마산 치즈가루 1kg", 5, 17000), ("생크림 1L", 28, 7000))


def test_perfect_match_is_one():
    s = summarize([(GOLD, GOLD)])
    assert s["line_ok"] == 3
    assert s["line_acc"] == 1.0


def test_one_wrong_qty():
    pred = _doc(("베이컨 1kg", 3, 11500), ("파마산 치즈가루 1kg", 4, 17000), ("생크림 1L", 28, 7000))
    c = score_doc(GOLD, pred)
    assert (c["line_ok"], c["name_ok"], c["qty_ok"], c["price_ok"]) == (2, 3, 2, 3)
    assert summarize([(GOLD, pred)])["line_acc"] == 2 / 3


def test_missing_line():
    pred = _doc(("베이컨 1kg", 3, 11500), ("생크림 1L", 28, 7000))
    c = score_doc(GOLD, pred)
    assert (c["n_gold"], c["n_pred"], c["line_ok"], c["extra"]) == (3, 2, 2, 0)
    assert match_lines(GOLD["items"], pred["items"]) == [(0, 0), (1, None), (2, 1)]


def test_extra_pred_line():
    pred = _doc(("베이컨 1kg", 3, 11500), ("파마산 치즈가루 1kg", 5, 17000), ("생크림 1L", 28, 7000),
                ("합계", 1, 315500))
    c = score_doc(GOLD, pred)
    assert (c["line_ok"], c["extra"]) == (3, 1)


def test_name_ignores_whitespace():
    pred = _doc(("베이컨1kg", 3, 11500), ("파마산치즈가루 1 kg", 5, 17000), ("생 크림 1L", 28, 7000))
    assert score_doc(GOLD, pred)["line_ok"] == 3


def test_empty_pred():
    c = score_doc(GOLD, {"items": []})
    assert (c["n_gold"], c["n_pred"], c["line_ok"], c["extra"]) == (3, 0, 0, 0)
    assert summarize([(GOLD, {"items": []})])["line_acc"] == 0.0


def test_printed_and_handwritten_split():
    hand = _doc(("양파 20kg", 1, 25000), ("대파 1단", 2, 3000), handwritten=True)
    hand_pred = _doc(("양파 20kg", 1, 25000), ("대파 1단", 3, 3000))
    s = summarize([(GOLD, GOLD), (hand, hand_pred)])
    assert s["printed_acc"] == 1.0
    assert s["handwritten_acc"] == 0.5
    assert s["n_gold"] == 5
    assert s["line_acc"] == 4 / 5


# ---- 정답 종류별 정규화 · 하위 폴더 짝짓기 ----

import json  # noqa: E402

from weglee_ai.ocr.grading import gold_items, load_gold, pair_docs  # noqa: E402

ITEM = {"name": "베이컨 1kg", "unit": "팩", "qty": 3, "price": 11500, "amount": 34500}


def test_gold_statement_and_handwritten():
    assert gold_items({"type": "statement", "items": [ITEM]}) == ([ITEM], False)
    assert gold_items({"type": "statement_handwritten", "items": [ITEM]}) == ([ITEM], True)


def test_gold_mall_orders_concatenates_orders():
    other = dict(ITEM, name="생크림 1L")
    doc = {"type": "mall_orders", "orders": [{"items": [ITEM]}, {"items": [other, ITEM]}]}
    assert gold_items(doc) == ([ITEM, other, ITEM], False)


def test_gold_pos_rows_renamed():
    doc = {"type": "pos", "rows": [
        {"date": "10-01", "item": "펜네 1kg", "qty": 21, "price": 3870, "amount": 81270, "note": ""},
        {"item": "생크림 1L", "qty": 1, "price": 5900, "amount": 5900, "unit": "팩"},
    ]}
    items, hand = gold_items(doc)
    assert hand is False
    assert items == [{"name": "펜네 1kg", "qty": 21, "price": 3870, "amount": 81270},
                     {"name": "생크림 1L", "qty": 1, "price": 5900, "amount": 5900, "unit": "팩"}]


def test_gold_without_type_uses_items_and_handwritten():
    assert gold_items({"items": [ITEM], "handwritten": True}) == ([ITEM], True)
    assert gold_items({"items": [ITEM]}) == ([ITEM], False)


def test_gold_excluded_types_are_none():
    assert gold_items({"type": "chat", "items": [{"name": "올리브유", "qty": 2}]}) is None
    assert gold_items({"type": "tax_invoice_list", "rows": []}) is None
    assert gold_items({"type": "ledger", "rows": []}) is None


def _write(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8-sig")


def test_subfolder_pairing_by_relative_path(tmp_path):
    gold, pred = tmp_path / "gold", tmp_path / "pred"
    a = {"type": "statement", "items": [{"name": "베이컨 1kg", "qty": 3, "price": 11500}]}
    b = {"type": "pos", "rows": [{"item": "생크림 1L", "qty": 28, "price": 7000}]}
    _write(gold / "01" / "synth_001.json", a)
    _write(gold / "10" / "synth_001.json", b)
    _write(gold / "03" / "synth_001.json", {"type": "chat", "items": []})
    _write(gold / "03" / "synth_002.json", {"type": "chat", "items": []})
    # 예측: 01 은 정답, 10 은 01 의 품목을 그대로(섞이면 맞은 것처럼 보인다)
    _write(pred / "01" / "synth_001.json", {"items": a["items"]})
    _write(pred / "10" / "synth_001.json", {"items": a["items"]})
    golds, excluded = load_gold(gold)
    assert [g[0] for g in golds] == ["01/synth_001.json", "10/synth_001.json"]
    assert dict(excluded) == {"chat": 2}
    pairs = pair_docs(golds, pred)
    by_type = {t: summarize([(g, p)])["line_ok"] for t, g, p in pairs}
    assert by_type == {"statement": 1, "pos": 0}


def test_subfolder_missing_pred_counts_zero(tmp_path):
    gold = tmp_path / "gold"
    _write(gold / "x" / "synth_001.json", {"items": [{"name": "a", "qty": 1, "price": 1}]})
    golds, _ = load_gold(gold)
    (t, g, p), = pair_docs(golds, tmp_path / "없는폴더")
    assert p == {"items": []} and summarize([(g, p)])["n_gold"] == 1


# ---- 평가_지표_정의.md §3.1 (이름 정규화 · 이름 유사도만으로 짝짓기 · 정답 숫자 정리 · 추가 줄 비율) ----


def test_name_ignores_brackets_case_and_space():
    gold = _doc(("돈등심(국내산)", 1, 9000), ("Olive Oil", 2, 12000), ("치즈 [모짜]", 3, 5000))
    pred = _doc(("돈등심국내산", 1, 9000), ("olive oil", 2, 12000), ("치즈{모짜}", 3, 5000))
    c = score_doc(gold, pred)
    assert (c["name_ok"], c["line_ok"]) == (3, 3)


def test_bracket_content_is_kept():
    # 괄호 기호만 지우고 안 글자는 남긴다 → 원산지가 다르면 다른 이름
    c = score_doc(_doc(("돈등심(국내산)", 1, 9000)), _doc(("돈등심(수입산)", 1, 9000)))
    assert c["name_ok"] == 0


def test_same_numbers_do_not_beat_closer_name():
    # 숫자 가점이 없으므로 예측 대파는 숫자가 같은 정답 양파가 아니라 이름이 같은 정답 대파와 짝지어진다
    gold = _doc(("양파", 2, 1000), ("대파", 5, 3000))
    pred = _doc(("대파", 2, 1000))
    assert match_lines(gold["items"], pred["items"]) == [(0, None), (1, 0)]


def test_zero_name_similarity_never_pairs():
    # 이름이 한 글자도 안 겹치면 숫자가 같아도 짝짓지 않는다 → 정답 줄은 틀림, 예측 줄은 추가 줄
    gold, pred = _doc(("양파", 2, 1000)), _doc(("버터", 2, 1000))
    assert match_lines(gold["items"], pred["items"]) == [(0, None)]
    c = score_doc(gold, pred)
    assert (c["line_ok"], c["extra"]) == (0, 1)


def test_low_name_similarity_still_pairs_without_min_score():
    # 하한(MIN_SCORE)이 없으므로 유사도가 낮아도 0 보다 크면 짝짓는다
    gold, pred = _doc(("파마산 치즈가루 1kg", 5, 17000)), _doc(("치즈", 1, 1))
    assert match_lines(gold["items"], pred["items"]) == [(0, 0)]


def test_gold_numbers_with_comma_and_won():
    gold = {"items": [{"name": "베이컨", "qty": "1", "price": "1,000원"}]}
    pred = {"items": [{"name": "베이컨", "qty": 1, "price": 1000}]}
    c = score_doc(gold, pred)
    assert (c["qty_ok"], c["price_ok"], c["line_ok"]) == (1, 1, 1)


def test_unparsable_numbers_are_not_equal():
    gold = {"items": [{"name": "베이컨", "qty": "1 kg", "price": 1000}]}
    pred = {"items": [{"name": "베이컨", "qty": None, "price": 1000}]}
    assert score_doc(gold, pred)["qty_ok"] == 0


def test_extra_rate_is_extra_over_pred():
    pred = _doc(("베이컨 1kg", 3, 11500), ("파마산 치즈가루 1kg", 5, 17000), ("생크림 1L", 28, 7000),
                ("합계", 1, 315500))
    s = summarize([(GOLD, pred), (GOLD, {"items": []})])
    assert (s["extra"], s["n_pred"]) == (1, 4)
    assert s["extra_rate"] == 1 / 4
    assert summarize([(GOLD, {"items": []})])["extra_rate"] is None


def test_duplicate_names_reversed_order_all_correct():
    # 같은 이름 줄이 여럿이면 유사도가 같으니 수량·단가가 맞는 쪽과 짝짓는다
    gold = _doc(("양파", 2, 1000), ("양파", 3, 1500))
    pred = _doc(("양파", 3, 1500), ("양파", 2, 1000))
    assert match_lines(gold["items"], pred["items"]) == [(0, 1), (1, 0)]
    assert score_doc(gold, pred)["line_ok"] == 2


def test_duplicate_names_one_missing_keeps_the_right_one():
    gold = _doc(("양파", 2, 1000), ("양파", 3, 1500))
    pred = _doc(("양파", 3, 1500))
    assert match_lines(gold["items"], pred["items"]) == [(0, None), (1, 0)]
    assert score_doc(gold, pred)["line_ok"] == 1


def test_duplicate_numbers_never_beat_name_similarity():
    # 숫자는 동점일 때만 본다 — 이름이 더 가까운 쪽이 숫자 맞는 쪽보다 먼저
    gold = _doc(("대파", 2, 1000), ("양파", 5, 3000))
    pred = _doc(("양파", 2, 1000))
    assert match_lines(gold["items"], pred["items"]) == [(0, None), (1, 0)]


def test_fullwidth_brackets_and_letters_are_normalized():
    for g, p in [("치즈［모짜］", "치즈모짜"), ("치즈｛모짜｝", "치즈모짜"), ("치즈【모짜】", "치즈모짜"),
                 ("치즈〔모짜〕", "치즈모짜"), ("치즈「모짜」", "치즈모짜"), ("치즈<모짜>", "치즈모짜"),
                 ("ＯＬＩＶＥ Ｏｉｌ", "olive oil")]:
        c = score_doc({"items": [{"name": g, "qty": 1, "price": 1}]}, {"items": [{"name": p, "qty": 1, "price": 1}]})
        assert c["name_ok"] == 1, (g, p)


def test_levenshtein_similarity_decides_pairing():
    # 편집 거리로는 간장돼지갈비찜(거리 3)이 돼지갈비양념(거리 4)보다 양념돼지갈비에 가깝다 (difflib 은 반대)
    gold = _doc(("돼지갈비양념", 1, 1), ("간장돼지갈비찜", 2, 2))
    pred = _doc(("양념돼지갈비", 9, 9))
    assert match_lines(gold["items"], pred["items"]) == [(0, None), (1, 0)]
