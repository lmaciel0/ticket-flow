import '@mantine/core/styles.css'

import { MantineProvider, Title } from '@mantine/core'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

// Temporary screen: Task 3 replaces it with the providers and the routes.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider>
      <Title p="xl">ticket-flow</Title>
    </MantineProvider>
  </StrictMode>,
)
