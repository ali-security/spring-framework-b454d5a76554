/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.accept;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.util.UriUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * A {@code ContentNegotiationStrategy} that resolves the file extension in the
 * request path to a key to be used to look up a media type.
 *
 * <p>If the file extension is not found in the explicit registrations provided
 * to the constructor, the {@link MediaTypeFactory} is used as a fallback
 * mechanism.
 *
 * @author Rossen Stoyanchev
 * @since 3.2
 * @deprecated as of 5.2.4. See class-level note in
 * {@link ContentNegotiationManagerFactoryBean} on the deprecation of path
 * extension config options.
 */
@Deprecated
public class PathExtensionContentNegotiationStrategy extends AbstractMappingContentNegotiationStrategy {

	private static final String MIME_TYPES_FILE_NAME = "/org/springframework/http/mime.types";

	/** Reverse lookup of the file extensions that {@link MediaTypeFactory} resolves per media type. */
	@Nullable
	private static volatile Map<MediaType, List<String>> mediaTypeFactoryFileExtensions;


	private UrlPathHelper urlPathHelper = new UrlPathHelper();


	/**
	 * Create an instance without any mappings to start with. Mappings may be added
	 * later on if any extensions are resolved through the Java Activation framework.
	 */
	public PathExtensionContentNegotiationStrategy() {
		this(null);
	}

	/**
	 * Create an instance with the given map of file extensions and media types.
	 */
	public PathExtensionContentNegotiationStrategy(@Nullable Map<String, MediaType> mediaTypes) {
		super(mediaTypes);
		setUseRegisteredExtensionsOnly(false);
		setIgnoreUnknownExtensions(true);
		this.urlPathHelper.setUrlDecode(false);
	}


	/**
	 * Configure a {@code UrlPathHelper} to use in {@link #getMediaTypeKey}
	 * in order to derive the lookup path for a target request URL path.
	 * @since 4.2.8
	 */
	public void setUrlPathHelper(UrlPathHelper urlPathHelper) {
		this.urlPathHelper = urlPathHelper;
	}

	/**
	 * Indicate whether to use the Java Activation Framework as a fallback option
	 * to map from file extensions to media types.
	 * @deprecated as of 5.0, in favor of {@link #setUseRegisteredExtensionsOnly(boolean)}.
	 */
	@Deprecated
	public void setUseJaf(boolean useJaf) {
		setUseRegisteredExtensionsOnly(!useJaf);
	}

	@Override
	@Nullable
	protected String getMediaTypeKey(NativeWebRequest webRequest) {
		HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
		if (request == null) {
			return null;
		}
		// Ignore LOOKUP_PATH attribute, use our own "fixed" UrlPathHelper with decoding off
		String path = this.urlPathHelper.getLookupPathForRequest(request);
		String extension = UriUtils.extractFileExtension(path);
		return (StringUtils.hasText(extension) ? extension.toLowerCase(Locale.ROOT) : null);
	}

	/**
	 * Return the file extensions registered for the given media type and, unless
	 * {@link #setUseRegisteredExtensionsOnly useRegisteredExtensionsOnly} is set,
	 * also the unregistered file extensions that {@link MediaTypeFactory} resolves
	 * to it. Media types resolved from request paths via {@link MediaTypeFactory}
	 * are not added to the registered mappings, so their file extensions are
	 * derived from the fixed set of known file extensions instead.
	 */
	@Override
	public List<String> resolveFileExtensions(MediaType mediaType) {
		List<String> fileExtensions = super.resolveFileExtensions(mediaType);
		if (isUseRegisteredExtensionsOnly()) {
			return fileExtensions;
		}
		List<String> knownFileExtensions = getMediaTypeFactoryFileExtensions().get(mediaType);
		if (knownFileExtensions == null) {
			return fileExtensions;
		}
		List<String> result = new ArrayList<>(fileExtensions);
		for (String extension : knownFileExtensions) {
			if (!result.contains(extension) && lookupMediaType(extension) == null) {
				result.add(extension);
			}
		}
		return result;
	}

	/**
	 * A public method exposing the knowledge of the path extension strategy to
	 * resolve file extensions to a {@link MediaType} in this case for a given
	 * {@link Resource}. The method first looks up any explicitly registered
	 * file extensions first and then falls back on {@link MediaTypeFactory} if available.
	 * @param resource the resource to look up
	 * @return the MediaType for the extension, or {@code null} if none found
	 * @since 4.3
	 */
	@Nullable
	public MediaType getMediaTypeForResource(Resource resource) {
		Assert.notNull(resource, "Resource must not be null");
		MediaType mediaType = null;
		String filename = resource.getFilename();
		String extension = StringUtils.getFilenameExtension(filename);
		if (extension != null) {
			mediaType = lookupMediaType(extension);
		}
		if (mediaType == null) {
			mediaType = MediaTypeFactory.getMediaType(filename).orElse(null);
		}
		return mediaType;
	}


	private static Map<MediaType, List<String>> getMediaTypeFactoryFileExtensions() {
		Map<MediaType, List<String>> fileExtensions = mediaTypeFactoryFileExtensions;
		if (fileExtensions == null) {
			fileExtensions = parseMediaTypeFactoryFileExtensions();
			mediaTypeFactoryFileExtensions = fileExtensions;
		}
		return fileExtensions;
	}

	/**
	 * Build a reverse lookup from media type to file extensions, based on the
	 * {@code mime.types} file used by {@link MediaTypeFactory}, keeping each
	 * file extension only for the media type that {@link MediaTypeFactory}
	 * resolves it to.
	 */
	private static Map<MediaType, List<String>> parseMediaTypeFactoryFileExtensions() {
		InputStream is = MediaTypeFactory.class.getResourceAsStream(MIME_TYPES_FILE_NAME);
		Assert.state(is != null, MIME_TYPES_FILE_NAME + " not found in classpath");
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII))) {
			Map<MediaType, List<String>> result = new HashMap<>();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.charAt(0) == '#') {
					continue;
				}
				String[] tokens = StringUtils.tokenizeToStringArray(line, " \t\n\r\f");
				for (int i = 1; i < tokens.length; i++) {
					String extension = tokens[i].toLowerCase(Locale.ROOT);
					MediaType mediaType = MediaTypeFactory.getMediaType("file." + extension).orElse(null);
					if (mediaType != null) {
						List<String> extensions = result.computeIfAbsent(mediaType, key -> new ArrayList<>());
						if (!extensions.contains(extension)) {
							extensions.add(extension);
						}
					}
				}
			}
			result.replaceAll((mediaType, extensions) -> Collections.unmodifiableList(extensions));
			return Collections.unmodifiableMap(result);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not read " + MIME_TYPES_FILE_NAME, ex);
		}
	}

}
