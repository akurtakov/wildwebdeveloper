/*******************************************************************************
 * Copyright (c) 2020, 2026 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Pierre-Yves Bigourdan - Allow configuring directory of ESLint package
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.eslint;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.lsp4e.client.DefaultLanguageClient;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.DocumentDiagnosticParams;
import org.eclipse.lsp4j.DocumentDiagnosticReport;
import org.eclipse.lsp4j.FullDocumentDiagnosticReport;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.wildwebdeveloper.jsts.ui.preferences.JSTSPreferenceServerConstants;
import org.eclipse.wildwebdeveloper.util.FileUtils;

public class ESLintClientImpl extends DefaultLanguageClient implements ESLintLanguageServerExtension {

	private static final java.util.Set<String> ESLINT_EXTENSIONS = java.util.Set.of(
			"js", "jsx", "ts", "tsx", "mjs", "cjs"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

	/** Tracks open files that ESLint should validate */
	private static final java.util.Set<IPath> OPEN_ESLINT_FILES = ConcurrentHashMap.newKeySet();

	/** De-dupes diagnostic pulls so repeated refresh requests do not overlap for the same file */
	private static final ConcurrentHashMap<String, CompletableFuture<Void>> IN_FLIGHT_REFRESHES = new ConcurrentHashMap<>();

	static {
		FileBuffers.getTextFileBufferManager().addFileBufferListener(new IFileBufferListener() {
			@Override public void bufferContentAboutToBeReplaced(IFileBuffer buffer) { /* no-op */ }
			@Override public void bufferContentReplaced(IFileBuffer buffer) { /* no-op */ }
			@Override public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) { /* no-op */ }
			@Override public void stateChangeFailed(IFileBuffer buffer) { /* no-op */ }
			@Override public void stateChanging(IFileBuffer buffer) { /* no-op */ }
			@Override public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) { /* no-op */ }
			@Override public void underlyingFileDeleted(IFileBuffer buffer) { /* no-op */ }
			@Override public void underlyingFileMoved(IFileBuffer buffer, IPath path) { /* no-op */ }

			@Override
			public void bufferCreated(IFileBuffer buffer) {
				IPath location = buffer.getLocation();
				if (location != null && isESLintFile(location)) {
					OPEN_ESLINT_FILES.add(location);
				}
			}

			@Override
			public void bufferDisposed(IFileBuffer buffer) {
				IPath location = buffer.getLocation();
				if (location != null) {
					OPEN_ESLINT_FILES.remove(location);
				}
			}

			private boolean isESLintFile(IPath path) {
				String ext = path.getFileExtension();
				return ext != null && ESLINT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT));
			}
		});
	}

	@Override
	public CompletableFuture<Integer> confirmESLintExecution(Object param) {
		return CompletableFuture.completedFuture(Integer.valueOf(4));
	}

	@Override
	public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
		ConfigurationItem configurationItem = configurationParams.getItems().get(0);

		Map<String, Object> config = new HashMap<>(6, 1.f);

		// search first for the highest directory that has a package.json file
		// to be set as the workspaceFolder below
		// then when the workingDirectory is set in mode:auto the eslint server code will look up
		// until the workspaceFolder which folder is best suited for its workingDirectoy (where the config files are in)
		// also this workspaceFolder is also used to find the node models (eslint module)
		// because we set the nodePath below to this same directory.
		File highestPackageJsonDir = FileUtils.uriToFile(configurationItem.getScopeUri()).getParentFile();
		File parentFile = highestPackageJsonDir;
		while (parentFile != null) {
			if (new File(parentFile, "package.json").exists()) highestPackageJsonDir = parentFile;
			parentFile = parentFile.getParentFile();
		}

		// `pre-release/2.3.0`: Disable using experimental Flat Config system
		config.put("experimental", Collections.emptyMap());

		// `pre-release/2.3.0`: Add stub `problems` settings due to:
		//   ESLint: Cannot read properties of undefined (reading \u0027shortenToSingleLine\u0027). Please see the \u0027ESLint\u0027 output channel for details.
		config.put("problems", Collections.emptyMap());

		config.put("workspaceFolder", Collections.singletonMap("uri", FileUtils.toUri(highestPackageJsonDir).toString()));

		// if you set a workspaceFolder and then the working dir in auto mode eslint will try to get to the right config location.
		config.put("workingDirectory", Collections.singletonMap("mode", "auto"));

		// this should not point to a nodejs executable but to a parent directory containing the ESLint package
		config.put("nodePath",getESLintPackageDir(highestPackageJsonDir));

		config.put("validate", "on");
		config.put("run", "onType");
		config.put("rulesCustomizations", Collections.emptyList());

		config.put("codeAction", Map.of("disableRuleComment", Map.of("enable", "true", "location", "separateLine"),
										"showDocumentation", Collections.singletonMap("enable", "true")));
		return CompletableFuture.completedFuture(Collections.singletonList(config));
	}

	private String getESLintPackageDir(File highestPackageJsonDir) {
		String eslintNodePath = JSTSPreferenceServerConstants.getESLintNodePath();

		// check if user specified a valid absolute path
		File eslintNodeFileUsingAbsolutePath = new File(eslintNodePath);
		if (eslintNodeFileUsingAbsolutePath.exists()) {
			return eslintNodeFileUsingAbsolutePath.getAbsolutePath();
		}

		// check if user specified a valid project-relative path
		File eslintNodeFileUsingProjectRelativePath = new File(highestPackageJsonDir.getAbsolutePath(), eslintNodePath);
		if (eslintNodeFileUsingProjectRelativePath.exists()) {
			return eslintNodeFileUsingProjectRelativePath.getAbsolutePath();
		}

		// fall back to the folder containing "node_modules"
		return highestPackageJsonDir.getAbsolutePath();
	}

	@Override
	public CompletableFuture<Void> eslintStatus(Object o) {
		// ignore for now
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> openDoc(Map<String,String> data) {
		if (data.containsKey("url")) {
			Display.getDefault().asyncExec(() -> {
				IWorkbenchBrowserSupport browserSupport = PlatformUI.getWorkbench().getBrowserSupport();
				try {
					browserSupport.createBrowser("openDoc").openURL(new URL(data.get("url")));
				} catch (Exception e) {
					ILog.get().error(e.getMessage(), e);
				}
			});
		}
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> noLibrary(Map<String,Map<String,String>> data) {
		MessageParams params = new MessageParams(MessageType.Info, "No ES Library found for file: " + data.get("source").get("uri"));
		logMessage(params);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> noConfig(Map<String, Map<String, String>> data) {
		MessageParams params = new MessageParams(MessageType.Info, "No ES Configuration found for file: " + data.get("source").get("uri")
				+ ": " + data.get("message"));
		logMessage(params);
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Handle diagnostic refresh requests from the server (required for ESLint v3.x diagnostic pull mode).
	 * In ESLint v3.x, the server uses diagnostic pull instead of push, so it sends refreshDiagnostics
	 * when diagnostics need to be re-fetched. We respond by pulling diagnostics for all open ESLint files
	 * and feeding them through publishDiagnostics so LSP4E creates the standard org.eclipse.lsp4e.diagnostic markers.
	 */
	@Override
	public CompletableFuture<Void> refreshDiagnostics() {
		final var languageServer = getLanguageServer();
		final var paths = new ArrayList<>(OPEN_ESLINT_FILES);

		for (IPath path : paths) {
			final IResource resource = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
			if (!(resource instanceof IFile file) || !file.exists()) {
				OPEN_ESLINT_FILES.remove(path);
				continue;
			}
			final String uri = file.getLocationURI().toString();
			final String key = uri + "@" + System.identityHashCode(languageServer); //$NON-NLS-1$

			IN_FLIGHT_REFRESHES.compute(key, (k, existing) -> {
				if (existing != null && !existing.isDone())
					return existing;

				final var params = new DocumentDiagnosticParams();
				params.setTextDocument(new TextDocumentIdentifier(uri));

				final CompletableFuture<Void> started = languageServer.getTextDocumentService()
					.diagnostic(params)
					.thenAccept((DocumentDiagnosticReport report) -> {
						if (report != null && report.isLeft()) {
							FullDocumentDiagnosticReport full = report.getLeft();
							if (full != null) {
								final var publishParams = new PublishDiagnosticsParams();
								publishParams.setUri(uri);
								publishParams.setDiagnostics(full.getItems() != null ? full.getItems() : List.of());
								publishDiagnostics(publishParams);
							}
						}
					})
					.exceptionally(ex -> {
						ILog.get().warn("ESLint diagnostic pull failed for: " + uri, ex); //$NON-NLS-1$
						return null;
					});

				started.whenComplete((v, ex) -> IN_FLIGHT_REFRESHES.remove(k, started));
				return started;
			});
		}
		return CompletableFuture.completedFuture(null);
	}
}
