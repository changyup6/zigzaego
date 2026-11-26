import base64
import json

from ocr_fakes import ENV, FakePost, _chat_ok

from weglee_ai.ocr import methods, schema

# ---- 요청 모양 ----


def test_method_b_request_has_data_image_url():
    post = FakePost(gms=[_chat_ok()])
    doc = methods.method_b(b"\x89PNGfake", "png", ENV, post=post)
    assert doc["items"][0]["name"] == "베이컨 1kg"
    assert len(post.calls) == 1  # CLOVA 는 부르지 않는다
    content = post.calls[0][2]["messages"][-1]["content"]
    urls = [c["image_url"]["url"] for c in content if c.get("type") == "image_url"]
    assert urls and urls[0].startswith("data:image/png;base64,")
    assert base64.b64decode(urls[0].split(",", 1)[1]) == b"\x89PNGfake"
    assert any(c.get("type") == "text" and schema.PROMPT in c["text"] for c in content)


def test_method_a_request_sends_clova_text_only_to_gms():
    clova = {"images": [{"fields": [{"inferText": "베이컨", "lineBreak": False}, {"inferText": "3", "lineBreak": True}]}]}
    post = FakePost(gms=[_chat_ok()], clova=[(200, json.dumps(clova, ensure_ascii=False))])
    doc = methods.method_a(b"img", "jpg", ENV, post=post)
    assert doc["items"][0]["qty"] == 3
    assert [c[0] for c in post.calls] == [ENV["CLOVA_OCR_URL"], ENV["GMS_BASE_URL"] + "/chat/completions"]
    text = json.dumps(post.calls[1][2]["messages"], ensure_ascii=False)
    assert "베이컨 3" in text and "data:image/" not in text
    assert post.calls[1][1]["Authorization"] == "Bearer gms-key"
