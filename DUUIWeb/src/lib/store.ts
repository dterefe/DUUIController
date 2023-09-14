import { writable, type Writable } from 'svelte/store'
import { blankPipeline, dummyPipeline, type DUUIPipeline, type DUUIPipelineComponent } from './data'

export const pipelineStore: Writable<DUUIPipeline[]> = writable([dummyPipeline])
export const componentsStore: Writable<DUUIPipelineComponent[]> = writable([])
export const currentPipelineStore: Writable<DUUIPipeline> = writable(blankPipeline())
export const pipelineFilterStore: Writable<string[]> = writable([])