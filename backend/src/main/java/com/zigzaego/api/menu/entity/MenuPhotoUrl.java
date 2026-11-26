package com.zigzaego.api.menu.entity;

import java.net.URI;
import java.util.List;

/** 수집한 URL만 브라우저에 전달한다. 이미지를 서버에서 가져오지는 않는다. */
public final class MenuPhotoUrl {

	private MenuPhotoUrl() {
	}

	public static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			String photo = value.strip();
			URI uri = URI.create(photo);
			return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null && uri.getUserInfo() == null ? photo : null;
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	public static String first(List<String> urls) {
		return urls == null ? null : urls.stream().map(MenuPhotoUrl::normalize)
				.filter(url -> url != null).findFirst().orElse(null);
	}
}
