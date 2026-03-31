/*******************************************************************************
 * Copyright (c) 2019, 2026 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.wildwebdeveloper.yaml;

import static org.eclipse.wildwebdeveloper.yaml.ui.preferences.YAMLPreferenceServerConstants.isMatchYamlSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4e.client.DefaultLanguageClient;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;
import org.eclipse.wildwebdeveloper.ui.preferences.Settings;
import org.eclipse.wildwebdeveloper.yaml.ui.preferences.YAMLPreferenceServerConstants;

/**
 * YAML language client implementation.
 * 
 */
public class YamlLanguageClientImpl extends DefaultLanguageClient {

	private static final String EDITOR_SECTION = "editor";
	private static final String YAML_LANGUAGE_SECTION = "[yaml]";

	@Override
	public CompletableFuture<List<Object>> configuration(ConfigurationParams params) {
		return CompletableFuture.supplyAsync(() -> {
			List<Object> settings = new ArrayList<>();
			for (ConfigurationItem item : params.getItems()) {
				String section = item.getSection();
				if (isMatchYamlSection(section)) {
					// See https://github.com/redhat-developer/yaml-language-server/blob/c4b56b155eae1b8aa53817b7caef7dd1032b93ff/src/languageserver/handlers/settingsHandlers.ts#L42
					// 'yaml' section, returns the yaml settings
					Settings yamlSettings = YAMLPreferenceServerConstants.getGlobalSettings();
					settings.add(yamlSettings.findSettings(section.split("[.]")));
				} else if (EDITOR_SECTION.equals(section)) {
					// See https://github.com/redhat-developer/yaml-language-server/blob/c4b56b155eae1b8aa53817b7caef7dd1032b93ff/src/languageserver/handlers/settingsHandlers.ts#L43
					// 'editor' section: provide editor indentation settings from Eclipse preferences
					var editorPrefs = EditorsUI.getPreferenceStore();
					Map<String, Object> editorSettings = new HashMap<>();
					editorSettings.put("detectIndentation", false);
					editorSettings.put("insertSpaces",
							editorPrefs.getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SPACES_FOR_TABS));
					editorSettings.put("tabSize",
							editorPrefs.getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH));
					settings.add(editorSettings);
				} else if (YAML_LANGUAGE_SECTION.equals(section)) {
					// See https://github.com/redhat-developer/yaml-language-server/blob/c4b56b155eae1b8aa53817b7caef7dd1032b93ff/src/languageserver/handlers/settingsHandlers.ts#L43
					// '[yaml]' section: per-language editor indentation overrides
					var editorPrefs = EditorsUI.getPreferenceStore();
					Map<String, Object> yamlEditorSettings = new HashMap<>();
					yamlEditorSettings.put("editor.tabSize",
							editorPrefs.getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH));
					yamlEditorSettings.put("editor.insertSpaces",
							editorPrefs.getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SPACES_FOR_TABS));
					settings.add(yamlEditorSettings);
				} else {
					// Unknown section
					settings.add(null);
				}
			}
			return settings;
		});
	}

}
