const tsPlugin = require("@typescript-eslint/eslint-plugin");
const tsParser = require("@typescript-eslint/parser");

module.exports = [
	{
		plugins: {
			"@typescript-eslint": tsPlugin,
		},
		languageOptions: {
			parser: tsParser,
		},
		rules: {
			indent: "error",
		},
	},
];
