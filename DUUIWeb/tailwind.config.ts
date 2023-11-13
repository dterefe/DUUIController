import { join } from 'path'
import type { Config } from 'tailwindcss'
import { skeleton } from '@skeletonlabs/tw-plugin'
import forms from '@tailwindcss/forms'
import { Theme } from './src/theme'

export default {
	darkMode: 'class',
	content: [
		'./src/**/*.{html,js,svelte,ts}',
		join(require.resolve('@skeletonlabs/skeleton'), '../**/*.{html,js,svelte,ts}')
	],
	theme: {
		extend: {
			animation: {
				'spin-slow': 'spin 2s linear infinite',
				hourglass: 'turn 4s linear infinite'
			},
			keyframes: {
				turn: {
					'0%': { transform: 'rotate( 0.0deg)' },
					'10%': { transform: 'rotate(90.0deg)' },
					'20%': { transform: 'rotate(180.0deg)' },
					'30%': { transform: 'rotate(180.0deg)' },
					'40%': { transform: 'rotate(180.0deg)' },
					'50%': { transform: 'rotate(180.0deg)' },
					'60%': { transform: 'rotate(270.0deg)' },
					'70%': { transform: 'rotate(360.0deg)' },
					'80%': { transform: 'rotate(360.0deg)' },
					'90%': { transform: 'rotate(360.0deg)' },
					'100%': { transform: 'rotate(360.0deg)' }
				}
			}
		}
	},
	plugins: [
		forms,
		skeleton({
			themes: {
				custom: [Theme],
				preset: [
					{
						name: 'rocket',
						enhancements: true
					}
				]
			}
		})
	]
} satisfies Config
