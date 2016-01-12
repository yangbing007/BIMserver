package org.bimserver.cache;

/******************************************************************************
 * Copyright (C) 2009-2016  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.activation.DataSource;

import org.bimserver.BimServer;
import org.bimserver.utils.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewDiskCacheManager {

	private static final Logger LOGGER = LoggerFactory.getLogger(NewDiskCacheManager.class);
	private final Path cacheDir;
	private final BimServer bimServer;
	private final Set<String> cachedFileNames = new HashSet<>();
	private final Map<String, NewDiskCacheOutputStream> busyCaching = new HashMap<>();

	public NewDiskCacheManager(BimServer bimServer, Path cacheDir) {
		this.bimServer = bimServer;
		this.cacheDir = cacheDir;
		try {
			if (!Files.exists(cacheDir)) {
				Files.createDirectory(cacheDir);
			}
			for (Path file : PathUtils.list(this.cacheDir)) {
				if (file.getFileName().toString().endsWith(".__tmp")) {
					Files.delete(file);
				} else {
					cachedFileNames.add(file.getFileName().toString());
				}
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
	}
	
	public boolean contains(DownloadDescriptor downloadDescriptor) {
		if (isEnabled()) {
			String cacheKey = downloadDescriptor.getCacheKey();
			synchronized (busyCaching) {
				if (busyCaching.containsKey(cacheKey)) {
					return true;
				}
			}
			synchronized (cachedFileNames) {
				return cachedFileNames.contains(cacheKey);
			}
		} else {
			return false;
		}
	}
	
	public boolean isEnabled() {
		return bimServer.getServerSettingsCache().getServerSettings().getCacheOutputFiles();
	}

	public DataSource get(DownloadDescriptor downloadDescriptor) {
		if (isEnabled()) {
			String cacheKey = downloadDescriptor.getCacheKey();
			NewDiskCacheOutputStream diskCacheOutputStream = null;
			synchronized (busyCaching) {
				diskCacheOutputStream = busyCaching.get(cacheKey);
			}
			if (diskCacheOutputStream != null) {
				try {
					LOGGER.info("Waiting for " + cacheKey);
					diskCacheOutputStream.waitForFinish();
				} catch (InterruptedException e) {
					LOGGER.error("", e);
				}
			}
			Path file = cacheDir.resolve(cacheKey);
			if (!Files.exists(file)) {
				LOGGER.error("File " + file.getFileName().toString() + " not found in cache");
			} else {
				LOGGER.info("Reading from cache " + cacheKey);
				FileInputStreamDataSource fileInputStreamDataSource = new FileInputStreamDataSource(file);
				fileInputStreamDataSource.setName(downloadDescriptor.getFileNameWithoutExtension());
				return fileInputStreamDataSource;
			}
		}
		return null;
	}

	public NewDiskCacheOutputStream startCaching(DownloadDescriptor downloadDescriptor) {
		try {
			String cacheKey = downloadDescriptor.getCacheKey();
			LOGGER.info("Start caching " + cacheKey);
			NewDiskCacheOutputStream out = new NewDiskCacheOutputStream(this, cacheDir.resolve(cacheKey), downloadDescriptor);
			synchronized (busyCaching) {
				busyCaching.put(cacheKey, out);
			}
			return out;
		} catch (FileNotFoundException e) {
			LOGGER.error("", e);
		}
		return null;
	}

	public synchronized Integer cleanup() {
		int removed = 0;
		try {
			for (Path file : PathUtils.list(cacheDir)) {
				try {
					Files.delete(file);
					removed++;
				} catch (IOException e) {
					LOGGER.error("", e);
				}
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
		cachedFileNames.clear();
		return removed;
	}

	public void doneGenerating(NewDiskCacheOutputStream diskCacheOutputStream) {
		synchronized (busyCaching) {
			String cacheKey = diskCacheOutputStream.getDownloadDescriptor().getCacheKey();
			LOGGER.info("Done caching " + cacheKey);
			busyCaching.remove(cacheKey);
			cachedFileNames.add(cacheKey);
		}
	}

	public void remove(NewDiskCacheOutputStream diskCacheOutputStream) {
		String cacheKey = diskCacheOutputStream.getDownloadDescriptor().getCacheKey();
		LOGGER.info("Removing cache " + cacheKey);
		cachedFileNames.remove(cacheKey);
		synchronized (busyCaching) {
			busyCaching.remove(cacheKey);
		}
	}
}