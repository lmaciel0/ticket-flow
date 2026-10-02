import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { tokenStorage } from '../api/client'

// findBy*/waitFor give up after 1 s by default: too short when the whole suite runs in parallel.
configure({ asyncUtilTimeout: 5_000 })

// Without Vitest globals, Testing Library cannot register its own cleanup: we do it here.
afterEach(() => {
  cleanup()
  localStorage.clear()
  tokenStorage.clear()
})

// jsdom does not implement these browser APIs, and Mantine components use them.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverStub

// Textarea with autosize listens to font loading.
Object.defineProperty(document, 'fonts', {
  value: { addEventListener: vi.fn(), removeEventListener: vi.fn(), ready: Promise.resolve() },
})
