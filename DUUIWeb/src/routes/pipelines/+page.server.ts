import { redirect } from '@sveltejs/kit'
import type { PageServerLoad } from './$types'
import { API_URL } from '$lib/config'
import type { DUUIPipeline } from '$lib/duui/pipeline'

export const load: PageServerLoad = async ({ fetch, locals, cookies }) => {
	if (!locals.user) {
		throw redirect(300, '/user/auth/login')
	}
	const loadPipelines = async (): Promise<{ pipelines: DUUIPipeline[] }> => {
		const result = await fetch(API_URL + '/pipelines/user/all', {
			method: 'GET',
			mode: 'cors',
			headers: {
				authorization: cookies.get('session') || ''
			}
		})
		return await result.json()
	}

	return {
		pipelines: (await loadPipelines()).pipelines
	}
}
