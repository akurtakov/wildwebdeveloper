/*******************************************************************************
 * Copyright (c) 2026 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.yaml;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentModificationException;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.lsp4e.VersionedEdits;
import org.eclipse.lsp4e.operations.format.LSPFormatter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.DocumentProviderRegistry;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.wildwebdeveloper.Activator;
import org.eclipse.wildwebdeveloper.yaml.ui.preferences.YAMLPreferenceServerConstants;

/**
 * Triggers YAML formatting on save via the YAML language server, converting
 * indentation to the configured style (spaces by default).
 */
class YAMLFormatOnSaveListener implements IFileBufferListener {

	private static final int FORMAT_TIMEOUT_MS = 5_000;
	private static final String YAML_CONTENT_TYPE_ID = "org.eclipse.tm4e.language_pack.yaml"; //$NON-NLS-1$

	private final Set<IPath> formatting = Collections.synchronizedSet(new HashSet<>());
	private final LSPFormatter formatter = new LSPFormatter();

	@Override
	public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
		if (isDirty) {
			return; // Only format on save (dirty → clean transition)
		}
		IPath location = buffer.getLocation();
		if (location == null) {
			return;
		}
		IFile file = toWorkspaceFile(location);
		if (file == null || !file.exists() || !isYAMLFile(file)) {
			return;
		}
		if (!isFormattingEnabled()) {
			return;
		}
		if (!formatting.add(location)) {
			return; // Already formatting this file – loop prevention
		}
		Job.createSystem("Format YAML on save: " + file.getName(), monitor -> { //$NON-NLS-1$
			try {
				format(file, location);
			} finally {
				formatting.remove(location);
			}
		}).schedule();
	}

	private void format(IFile file, IPath location) {
		var editorInput = new FileEditorInput(file);
		IDocumentProvider docProvider = DocumentProviderRegistry.getDefault().getDocumentProvider(editorInput);
		if (docProvider == null) {
			return;
		}
		try {
			docProvider.connect(editorInput);
		} catch (CoreException ex) {
			ILog.get().error("Cannot connect document provider for YAML format-on-save: " + location, ex); //$NON-NLS-1$
			return;
		}
		try {
			IDocument doc = docProvider.getDocument(editorInput);
			if (doc == null) {
				return;
			}
			VersionedEdits edits;
			try {
				var result = formatter.requestFormatting(doc, new TextSelection(0, 0))
						.get(FORMAT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				if (result.isEmpty()) {
					return;
				}
				edits = result.get();
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception ex) {
				ILog.get().error("Error requesting YAML formatting on save: " + location, ex); //$NON-NLS-1$
				return;
			}
			docProvider.aboutToChange(doc);
			Display.getDefault().syncExec(() -> {
				try {
					edits.apply();
				} catch (ConcurrentModificationException | BadLocationException ex) {
					ILog.get().error("Error applying YAML formatting on save: " + location, ex); //$NON-NLS-1$
				}
			});
			docProvider.changed(doc);
			try {
				docProvider.saveDocument(new NullProgressMonitor(), editorInput, docProvider.getDocument(editorInput),
						true);
			} catch (CoreException ex) {
				ILog.get().error("Error saving YAML file after format: " + location, ex); //$NON-NLS-1$
			}
		} finally {
			docProvider.disconnect(editorInput);
		}
	}

	private static IFile toWorkspaceFile(IPath location) {
		var root = ResourcesPlugin.getWorkspace().getRoot();
		var res = root.findMember(location);
		if (res instanceof IFile file) {
			return file;
		}
		return root.getFileForLocation(location);
	}

	private static boolean isYAMLFile(IFile file) {
		try {
			IContentTypeManager ctm = Platform.getContentTypeManager();
			IContentType yamlCT = ctm.getContentType(YAML_CONTENT_TYPE_ID);
			if (yamlCT != null) {
				IContentType fileCT = ctm.findContentTypeFor(file.getName());
				if (fileCT != null && fileCT.isKindOf(yamlCT)) {
					return true;
				}
			}
		} catch (Exception ex) {
			ILog.get().warn(ex.getMessage(), ex);
		}
		// Fallback: cheap extension check
		String ext = file.getFileExtension();
		if (ext == null) {
			return false;
		}
		return switch (ext.toLowerCase(Locale.ROOT)) {
			case "yaml", "yml" -> true; //$NON-NLS-1$ //$NON-NLS-2$
			default -> false;
		};
	}

	private static boolean isFormattingEnabled() {
		return Activator.getDefault().getPreferenceStore()
				.getBoolean(YAMLPreferenceServerConstants.YAML_PREFERENCES_FORMAT_ENABLE);
	}

	@Override
	public void bufferContentAboutToBeReplaced(IFileBuffer buffer) {
	}

	@Override
	public void bufferContentReplaced(IFileBuffer buffer) {
	}

	@Override
	public void bufferCreated(IFileBuffer buffer) {
	}

	@Override
	public void bufferDisposed(IFileBuffer buffer) {
	}

	@Override
	public void stateChangeFailed(IFileBuffer buffer) {
	}

	@Override
	public void stateChanging(IFileBuffer buffer) {
	}

	@Override
	public void stateValidationChanged(IFileBuffer buffer, boolean isStateValidated) {
	}

	@Override
	public void underlyingFileDeleted(IFileBuffer buffer) {
	}

	@Override
	public void underlyingFileMoved(IFileBuffer buffer, IPath path) {
	}
}
