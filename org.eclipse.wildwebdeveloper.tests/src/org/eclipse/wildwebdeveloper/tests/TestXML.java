/*******************************************************************************
 * Copyright (c) 2019, 2022 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Mickael Istria (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.tests;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.lsp4e.operations.completion.LSContentAssistProcessor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.tests.harness.util.DisplayHelper;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(AllCleanRule.class)
class TestXML {

	private IProject project;
	private ICompletionProposal[] proposals;

	@BeforeEach
	public void setUpProject() throws CoreException {
		this.project = ResourcesPlugin.getWorkspace().getRoot().getProject(getClass().getName() + System.nanoTime());
		project.create(null);
		project.open(null);
	}

	@ParameterizedTest
	@CsvSource({ "blah.xml,<plugin></", "blah.xsl,FAIL", "blah.xsd,a<", "blah.dtd,<!--<!-- -->" })
	void testFile(String fileName, String content) throws Exception {
		final IFile file = project.getFile(fileName);
		file.create(new ByteArrayInputStream("FAIL".getBytes()), true, null);
		ITextEditor editor = (ITextEditor) IDE
				.openEditor(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file);
		editor.getDocumentProvider().getDocument(editor.getEditorInput()).set(content);
		assertTrue(DisplayHelper.waitForCondition(PlatformUI.getWorkbench().getDisplay(), 5000, () -> {
			try {
				return file.findMarkers("org.eclipse.lsp4e.diagnostic", true, IResource.DEPTH_ZERO).length != 0;
			} catch (CoreException e) {
				return false;
			}
		}), "Diagnostic not published");
	}

	@Test
	void testComplexXML() throws Exception {
		final IFile file = project.getFile("blah.xml");
		String content = "<layout:BlockLayoutCell\n" + "	xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"	\n"
				+ "    xsi:schemaLocation=\"sap.ui.layout https://openui5.hana.ondemand.com/downloads/schemas/sap.ui.layout.xsd\"\n"
				+ "	xmlns:layout=\"sap.ui.layout\">\n" + "    |\n" + "</layout:BlockLayoutCell>";
		int offset = content.indexOf('|');
		content = content.replace("|", "");
		file.create(new ByteArrayInputStream(content.getBytes()), true, null);
		AbstractTextEditor editor = (AbstractTextEditor) IDE.openEditor(
				PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage(), file,
				"org.eclipse.ui.genericeditor.GenericEditor");
		editor.getSelectionProvider().setSelection(new TextSelection(offset, 0));
		LSContentAssistProcessor processor = new LSContentAssistProcessor();
		proposals = processor.computeCompletionProposals(Utils.getViewer(editor), offset);
		DisplayHelper.sleep(editor.getSite().getShell().getDisplay(), 2000);
		assertTrue(proposals.length > 1);
	}
}
