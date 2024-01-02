<script lang="ts">
	import { v4 as uuidv4 } from 'uuid'
	import { goto, invalidateAll } from '$app/navigation'
	import PipelineComponent from '$lib/svelte/widgets/duui/PipelineComponent.svelte'
	import { isEqual, cloneDeep } from 'lodash'
	import {
		faArrowDownWideShort,
		faArrowLeft,
		faArrowUpWideShort,
		faCopy,
		faFileCircleCheck,
		faFileCircleXmark,
		faFileExport,
		faGears,
		faHourglass,
		faHourglassStart,
		faLayerGroup,
		faMap,
		faPause,
		faPlay,
		faPlus,
		faRefresh,
		faTrash,
		faWifi
	} from '@fortawesome/free-solid-svg-icons'
	import { type ModalSettings, getModalStore } from '@skeletonlabs/skeleton'
	import { dndzone, type DndEvent } from 'svelte-dnd-action'
	import Fa from 'svelte-fa'
	import { flip } from 'svelte/animate'

	import { getToastStore } from '@skeletonlabs/skeleton'
	import type { PageServerData } from './$types'
	import { datetimeToString, equals } from '$lib/utils/text'
	import { getDuration } from '$lib/utils/time'
	import { TabGroup, Tab } from '@skeletonlabs/skeleton'
	import { page } from '$app/stores'
	import { Api, makeApiCall } from '$lib/utils/api'
	import {
		documentStatusNamesString,
		getStatusIcon,
		info,
		success,
		variantError,
		variantSuccess
	} from '$lib/utils/ui'
	import { pipelineToExportableJson } from '$lib/duui/pipeline'
	import ActionButton from '$lib/svelte/widgets/action/ActionButton.svelte'
	import type { DUUIComponent } from '$lib/duui/component'
	import { Status } from '$lib/duui/monitor'
	import type { DUUIProcess } from '$lib/duui/process'
	import { markedForDeletionStore } from '$lib/store'
	import Mapper from '$lib/svelte/widgets/input/Mapper.svelte'
	import TextArea from '$lib/svelte/widgets/input/TextArea.svelte'
	import Text from '$lib/svelte/widgets/input/Text.svelte'
	import SpeedDial from '$lib/svelte/widgets/navigation/SpeedDial.svelte'
	import IconButton from '$lib/svelte/widgets/action/IconButton.svelte'

	export let data: PageServerData
	let { pipeline, processes } = data

	pipeline.components.forEach((c) => (c.id = uuidv4()))

	let pipelineCopy = cloneDeep(pipeline)

	const modalStore = getModalStore()
	const toastStore = getToastStore()

	let startingService: boolean = false
	let stoppingService: boolean = false

	let settings: Map<string, string> = new Map(Object.entries(pipeline.settings))
	let hasChanges: boolean = false

	let tableHeader: string[] = ['Started At', 'IO', '# Documents', 'Progress', 'Status', 'Duration']

	let sortIndex: number = 0
	let sortOrder: number = 1

	let pageIndex: number = 0
	let itemsPerPage: number = 10
	let numberOfPages: number = processes.length / itemsPerPage

	let filteredProcesses: DUUIProcess[] = []
	let sortedProcessses: DUUIProcess[] = processes
	let statusFilter: string = Status.Any

	let tabSet: number = +($page.url.searchParams.get('tab') || 0)

	const handleDndConsider = (event: CustomEvent<DndEvent<DUUIComponent>>) => {
		pipeline.components = [...event.detail.items]

		if (pipeline.components.length <= 1) {
			pipelineCopy.components = cloneDeep(pipeline.components)
		}
	}

	const handleDndFinalize = (event: CustomEvent<DndEvent<DUUIComponent>>) => {
		pipeline.components = [...event.detail.items]
		pipeline.components.forEach(
			(c) => (c.index = pipeline.components.map((c) => c.oid).indexOf(c.oid))
		)

		if (pipeline.components.length <= 1) {
			pipelineCopy.components = cloneDeep(pipeline.components)
		}
	}

	const updatePipeline = async () => {
		pipeline.settings = Object.fromEntries(settings.entries())
		pipeline.components = pipeline.components.map((c) => {
			return { ...c, index: pipeline.components.indexOf(c) }
		})

		let response = await makeApiCall(Api.Pipelines, 'PUT', pipeline)

		if (response.ok) {
			toastStore.trigger(success('Changes saved successfully'))
			pipelineCopy = cloneDeep(pipeline)
		}

		for (let oid of $markedForDeletionStore) {
			makeApiCall(Api.Components, 'DELETE', { oid: oid })
		}
	}

	const deletePipeline = async () => {
		new Promise<boolean>((resolve) => {
			const modal: ModalSettings = {
				type: 'component',
				component: 'deleteModal',
				meta: {
					title: 'Delete Pipeline',
					body: `Are you sure you want to delete ${pipeline.name}?`
				},
				response: (r: boolean) => {
					resolve(r)
				}
			}

			modalStore.trigger(modal)
		}).then(async (accepted: boolean) => {
			if (!accepted) return

			let response = await makeApiCall(Api.Pipelines, 'DELETE', { oid: pipeline.oid })
			if (response.ok) {
				goto('/pipelines')
				toastStore.trigger(info('Pipeline deleted successfully'))
			}
		})
	}

	const copyPipeline = async () => {
		let response = await makeApiCall(Api.Pipelines, 'POST', {
			...pipeline,
			name: pipeline.name + ' - Copy'
		})

		if (response.ok) {
			const data = await response.json()
			toastStore.trigger(info('Pipeline copied successfully'))
			invalidateAll()
			goto(`/pipelines/${data.oid}`, { invalidateAll: true, replaceState: true })
		}
	}

	const manageService = async () => {
		if (startingService || stoppingService) return
		pipeline.serviceStartTime === 0 ? startService() : stopService()
	}

	const startService = async () => {
		startingService = true
		toastStore.trigger(info(`Starting Service ${pipeline.name}`))
		let response = await makeApiCall(Api.Services, 'POST', pipeline)

		if (response.ok) {
			const json = await response.json()
			pipeline.serviceStartTime = json.serviceStartTime
			pipelineCopy.serviceStartTime = json.serviceStartTime

			toastStore.trigger(success(`Service ${pipeline.name} has been started`))
		}

		startingService = false
	}

	const stopService = async () => {
		stoppingService = true
		toastStore.trigger(info(`Stopping Service ${pipeline.name}`))

		let response = await makeApiCall(Api.Services, 'PUT', pipeline)

		if (response.ok) {
			pipeline.serviceStartTime = 0
			pipelineCopy.serviceStartTime = 0

			toastStore.trigger(info(`Service ${pipeline.name} has been stopped`))
		}

		stoppingService = false
	}

	const exportPipeline = () => {
		const blob = new Blob([JSON.stringify(pipelineToExportableJson(pipeline))], {
			type: 'application/json'
		})
		const url = URL.createObjectURL(blob)
		const anchor = document.createElement('a')
		anchor.href = url
		anchor.download = `${pipeline.name}.json`
		document.body.appendChild(anchor)
		anchor.click()
		document.body.removeChild(anchor)
		URL.revokeObjectURL(url)
	}

	const discardChanges = () => {
		pipeline = cloneDeep(pipelineCopy)
		settings = new Map(Object.entries(pipeline.settings))
	}

	$: {
		hasChanges = !isEqual(pipeline, pipelineCopy)

		if (settings) {
			hasChanges ||= !isEqual(Object.fromEntries(settings.entries()), pipeline.settings)
		}
	}

	$: {
		if (processes.length > 0) {
			filteredProcesses = processes.filter(
				(p) => equals(p.status, statusFilter) || statusFilter === '' || statusFilter === Status.Any
			)

			sortedProcessses = filteredProcesses
				.sort((a, b) => {
					if (sortIndex === 1) return a.input.source > b.input.source ? sortOrder : -sortOrder
					if (sortIndex === 2)
						return a.documentNames.length > b.documentNames.length ? sortOrder : -sortOrder
					if (sortIndex === 3) return a.progress > b.progress ? sortOrder : -sortOrder
					if (sortIndex === 4)
						return documentStatusNamesString.indexOf(a.status) >
							documentStatusNamesString.indexOf(b.status)
							? sortOrder
							: -sortOrder

					if (sortIndex === 5)
						return getDuration(a.startTime, a.endTime) > getDuration(b.startTime, b.endTime)
							? sortOrder
							: -sortOrder
					return a.startTime > b.startTime ? -sortOrder : sortOrder
				})
				.slice(itemsPerPage * pageIndex, itemsPerPage * (pageIndex + 1))

			numberOfPages = Math.floor(filteredProcesses.length / itemsPerPage)
			if (filteredProcesses.length % itemsPerPage === 0) numberOfPages -= 1
		}
	}

	const sortTable = (index: number) => {
		sortOrder = sortIndex !== index ? 1 : (sortOrder *= -1)
		sortIndex = index
	}
</script>

<SpeedDial>
	<svelte:fragment slot="content">
		<IconButton icon={faArrowLeft} on:click={() => goto('/pipelines')} />
		<IconButton icon={faFileExport} on:click={exportPipeline} />
		<IconButton icon={faCopy} on:click={copyPipeline} />
		<IconButton icon={faTrash} on:click={deletePipeline} />
		{#if startingService || stoppingService}
			<IconButton icon={faRefresh} _class="lg:ml-auto col-span-2 animate-spin-slow" />
		{:else if pipeline.serviceStartTime === 0}
			<IconButton icon={faPlay} on:click={manageService} _class="lg:ml-auto col-span-2 pl-1" />
		{:else}
			<IconButton
				icon={faPause}
				on:click={manageService}
				_class="lg:ml-auto col-span-2 variant-filled-success dark:variant-soft-success pl"
			/>
		{/if}
	</svelte:fragment>
</SpeedDial>

<svelte:head>
	<title>{pipeline.name}</title>
</svelte:head>

<div class="container h-full flex-col mx-auto flex gap-4 md:my-8">
	<div class="flex items-center md:items-end justify-between gap-4">
		<h1 class="h2">{pipeline.name}</h1>
		<div class="hidden md:flex items-center justify-between">
			{#if pipeline.lastUsed}
				<p>Last used: {datetimeToString(new Date(pipeline.lastUsed))}</p>
			{:else}
				<p>Last used: Never</p>
			{/if}
		</div>
		{#if pipeline.serviceStartTime !== 0}
			<Fa icon={faWifi} class="md:hidden text-success-400" />
		{/if}
	</div>
	<hr class="bg-surface-400/20 h-[1px] !border-0 rounded" />
	<div class="hidden md:grid grid-cols-2 md:grid-cols-3 lg:flex items-center justify-start gap-4">
		<ActionButton text="Back" icon={faArrowLeft} on:click={() => goto('/pipelines')} />
		<ActionButton text="Export" icon={faFileExport} on:click={exportPipeline} />
		<ActionButton text="Copy" icon={faCopy} on:click={copyPipeline} />
		<ActionButton text="Delete" icon={faTrash} on:click={deletePipeline} />

		{#if startingService}
			<ActionButton
				icon={faHourglassStart}
				text="Starting Service"
				_class="lg:ml-auto col-span-2"
			/>
		{:else if stoppingService}
			<ActionButton
				icon={faHourglass}
				text="Stopping Service"
				_class="lg:ml-auto col-span-2 animate-pulse"
			/>
		{:else if pipeline.serviceStartTime === 0}
			<ActionButton
				icon={faPlay}
				text="Start Service"
				on:click={manageService}
				_class="lg:ml-auto col-span-2"
			/>
		{:else}
			<ActionButton
				icon={faPause}
				text="Stop Service"
				on:click={manageService}
				_class="lg:ml-auto col-span-2"
			/>
		{/if}
	</div>

	<div class="bg-surface-100 dark:variant-soft-surface shadow-lg text-sm md:text-base">
		<TabGroup
			active="dark:variant-soft-primary variant-filled-primary"
			border="none"
			rounded="rounded-none"
			justify="grid grid-cols-3"
		>
			<Tab bind:group={tabSet} name="settings" value={0}>
				<div class="flex items-center justify-center gap-2">
					<Fa class="hidden md:block" icon={faGears} />
					<span>Settings</span>
				</div>
			</Tab>

			<Tab bind:group={tabSet} name="components" value={1}
				><div class="flex items-center justify-center gap-2">
					<Fa class="hidden md:block" icon={faMap} />
					<span>Components</span>
				</div></Tab
			>
			<Tab bind:group={tabSet} name="processes" value={2}
				><div class="flex items-center justify-center gap-2">
					<Fa class="hidden md:block" icon={faLayerGroup} />
					<span>Processes</span>
				</div></Tab
			>
		</TabGroup>
	</div>

	{#if tabSet === 0}
		<div class="grid md:grid-cols-2 gap-4">
			<div class="flex flex-col gap-4 bg-surface-100 dark:variant-soft-surface shadow-lg p-4">
				<Text label="Name" name="pipeline-name" bind:value={pipeline.name} />
				<TextArea
					label="Description"
					name="pipeline-description"
					bind:value={pipeline.description}
				/>
			</div>
			<div class="bg-surface-100 dark:variant-soft-surface p-4 shadow-lg rounded-none">
				<Mapper
					bind:map={settings}
					on:update={(event) => {
						pipeline.settings = Object.fromEntries(event.detail.map.entries())
					}}
				/>
			</div>
		</div>
	{:else if tabSet === 1}
		<div class="container space-y-4 bg-surface-100 dark:variant-soft-surface mx-auto shadow-lg p-4">
			<ul
				use:dndzone={{ items: pipeline.components, dropTargetStyle: {} }}
				on:consider={(event) => handleDndConsider(event)}
				on:finalize={(event) => handleDndFinalize(event)}
				class="grid gap-4 max-w-5xl mx-auto"
			>
				{#each pipeline.components as component (component.id)}
					<div animate:flip={{ duration: 300 }}>
						<PipelineComponent
							{component}
							on:update={updatePipeline}
							on:deleteComponent={(event) => {
								pipeline.components = pipeline.components.filter((c) => c.oid !== event.detail.oid)
								pipelineCopy = cloneDeep(pipeline)
							}}
						/>
					</div>
				{/each}
			</ul>
		</div>
	{:else if tabSet === 2}
		<ActionButton
			text="New Process"
			icon={faPlus}
			_class="mr-auto"
			on:click={() => goto('/process?oid=' + pipeline.oid)}
		/>
		<div class="overflow-hidden border-[1px] border-surface-200 dark:border-surface-500">
			<div
				class="header grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 bg-surface-100 dark:variant-soft-surface border-b-4 border-primary-500"
			>
				{#each tableHeader as column, index}
					<button
						class="btn bg-surface-100 px-4 dark:variant-soft-surface gap-4 justify-start items-center rounded-none p-3 text-left
						 {[3].includes(index)
							? 'hidden sm:inline-flex'
							: [2].includes(index)
							? 'hidden md:inline-flex'
							: [1, 5].includes(index)
							? 'hidden lg:inline-flex'
							: ''}"
						on:click={() => sortTable(index)}
					>
						<span>{column}</span>
						{#if sortIndex === index}
							<Fa icon={sortOrder === -1 ? faArrowDownWideShort : faArrowUpWideShort} />
						{/if}
					</button>
				{/each}
			</div>

			<div class=" overflow-hidden flex flex-col">
				{#each sortedProcessses as process}
					<button
						class="btn rounded-none first:border-t-0 border-t-[1px]
						dark:border-t-surface-500 dark:hover:variant-soft-primary hover:variant-filled-primary grid-cols-2 grid sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-8 p-3 px-4 text-left"
						on:click={() => goto(`/process/${process.oid}?limit=10&skip=0`)}
					>
						<p>{datetimeToString(new Date(process.startTime))}</p>
						<p class="hidden lg:block">{process.input.source} / {process.output.target}</p>
						<p class="hidden md:block">{process.documentNames.length}</p>
						<p class="hidden sm:flex items-center justify-between gap-4">
							<span
								>{((process.progress / process.documentNames.length) * 100 || 0).toFixed(2)} %</span
							>
							<span>{process.progress} / {process.documentNames.length}</span>
						</p>
						<p class="flex justify-start items-center gap-4">
							<Fa
								icon={getStatusIcon(process.status)}
								size="lg"
								class={equals(process.status, Status.Running) ? 'animate-spin-slow' : ''}
							/>
							<span>{process.status}</span>
						</p>
						<p class="hidden lg:block">
							{getDuration(process.startTime, process.endTime)}
						</p>
					</button>
				{/each}
			</div>
		</div>
	{/if}

	{#if hasChanges}
		<div class="flex items-center gap-4">
			<ActionButton
				text="Save changes"
				icon={faFileCircleCheck}
				on:click={updatePipeline}
				variant={variantSuccess}
			/>
			<ActionButton
				variant={variantError}
				text="Discard changes"
				icon={faFileCircleXmark}
				on:click={discardChanges}
			/>
		</div>
	{/if}
</div>