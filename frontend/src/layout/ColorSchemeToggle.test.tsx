import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderUi } from '../test/render'
import { ColorSchemeToggle } from './ColorSchemeToggle'

describe('ColorSchemeToggle', () => {
  it('switches between light and dark and updates its label', async () => {
    const user = userEvent.setup()
    renderUi(<ColorSchemeToggle />)

    await user.click(screen.getByRole('button', { name: 'Usar tema escuro' }))
    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'dark')

    await user.click(screen.getByRole('button', { name: 'Usar tema claro' }))
    expect(document.documentElement).toHaveAttribute('data-mantine-color-scheme', 'light')
  })
})
