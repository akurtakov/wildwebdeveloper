import tsParser from '@typescript-eslint/parser';

export default [
	{
		rules: {
			indent: 'error',
		},
	},
	{
		files: ['**/*.ts', '**/*.tsx'],
		languageOptions: {
			parser: tsParser,
		},
	},
];
