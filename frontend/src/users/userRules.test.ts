import { describe, expect, it } from 'vitest'
import type { User } from '../api/types'
import { whyUserIsLocked } from './userRules'

const manager: User = { id: 1, name: 'Gil', email: 'g@x.com', role: 'MANAGER', active: true, demo: false }

function user(changes: Partial<User>): User {
  return { id: 2, name: 'Ana', email: 'a@x.com', role: 'AGENT', active: true, demo: false, ...changes }
}

describe('whyUserIsLocked (same rules as the backend, which answers 409)', () => {
  it('locks demo accounts, so visitors cannot break the demo', () => {
    expect(whyUserIsLocked(user({ demo: true }), manager)).toBe('Contas de demonstração não podem ser alteradas.')
  })

  it('locks the manager own account', () => {
    expect(whyUserIsLocked(manager, manager)).toBe('Você não pode alterar a sua própria conta.')
  })

  it('leaves other accounts editable', () => {
    expect(whyUserIsLocked(user({}), manager)).toBeNull()
  })
})
