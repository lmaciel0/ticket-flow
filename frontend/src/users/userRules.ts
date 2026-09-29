import type { User } from '../api/types'

/**
 * Why the manager cannot change this account, or null if they can. The rule that depends on
 * data the screen does not have (an agent with unfinished tickets) stays in the backend: it
 * answers 409 "reatribua os chamados antes" and the screen shows that message.
 */
export function whyUserIsLocked(target: User, me: User): string | null {
  if (target.demo) {
    return 'Contas de demonstração não podem ser alteradas.'
  }
  if (target.id === me.id) {
    return 'Você não pode alterar a sua própria conta.'
  }
  return null
}
