import { notifications } from '@mantine/notifications'
import { MutationCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from './client'

export function showError(error: unknown) {
  notifications.show({
    color: 'red',
    title: 'Não foi possível concluir',
    message: error instanceof Error ? error.message : 'Algo deu errado. Tente de novo.',
  })
}

export function createQueryClient() {
  return new QueryClient({
    // Every failed action shows a notification, unless the screen shows the error itself
    // (forms mark their mutation with meta: { inlineError: true }).
    mutationCache: new MutationCache({
      onError: (error, _variables, _context, mutation) => {
        if (!mutation.meta?.inlineError) {
          showError(error)
        }
      },
    }),
    defaultOptions: {
      queries: {
        // 4xx answers will not change by asking again; network errors and 5xx get one retry.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
      },
    },
  })
}
