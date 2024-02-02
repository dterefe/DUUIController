import { API_URL } from '$lib/config'
import type { DUUIPipeline } from '$lib/duui/pipeline'

export async function POST({ request, cookies }) {
	const data: DUUIPipeline = await request.json()

	const response = await fetch(`${API_URL}/pipelines/${data.oid}/start`, {
		method: 'POST',
		mode: 'cors',
		headers: {
			Authorization: cookies.get('session') || ''
		}
	})

	return response
}

export async function PUT({ request, cookies }) {
	const data: DUUIPipeline = await request.json()

	const response = await fetch(`${API_URL}/pipelines/${data.oid}/stop`, {
		method: 'PUT',
		mode: 'cors',
		headers: {
			Authorization: cookies.get('session') || ''
		}
	})

	return response
}