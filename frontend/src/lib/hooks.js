import { useEffect, useEffectEvent } from 'react'

export function usePolling(enabled, intervalMs, task) {
  const run = useEffectEvent(task)
  useEffect(() => {
    if (!enabled) return undefined
    const timer = window.setInterval(() => run(), intervalMs)
    return () => window.clearInterval(timer)
  }, [enabled, intervalMs])
}
