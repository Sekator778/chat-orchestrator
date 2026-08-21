/**
 * Performance Utilities for Configuration Graph
 *
 * Provides debouncing, throttling, and other performance optimization utilities
 * for the configuration graph components.
 */

import { useRef, useCallback, useEffect, useState } from 'react'

/**
 * Creates a debounced version of a function
 * The function will only be called after the specified delay has passed
 * since the last invocation
 */
export function debounce<T extends (...args: Parameters<T>) => ReturnType<T>>(
  fn: T,
  delay: number
): (...args: Parameters<T>) => void {
  let timeoutId: ReturnType<typeof setTimeout> | null = null
  return (...args: Parameters<T>) => {
    if (timeoutId) {
      clearTimeout(timeoutId)
    }
    timeoutId = setTimeout(() => {
      fn(...args)
      timeoutId = null
    }, delay)
  }
}

/**
 * Creates a throttled version of a function
 * The function will only be called at most once per specified interval
 */
export function throttle<T extends (...args: Parameters<T>) => ReturnType<T>>(
  fn: T,
  interval: number
): (...args: Parameters<T>) => void {
  let lastCall = 0
  let timeoutId: ReturnType<typeof setTimeout> | null = null
  return (...args: Parameters<T>) => {
    const now = Date.now()
    const remaining = interval - (now - lastCall)
    if (remaining <= 0) {
      if (timeoutId) {
        clearTimeout(timeoutId)
        timeoutId = null
      }
      lastCall = now
      fn(...args)
    } else if (!timeoutId) {
      timeoutId = setTimeout(() => {
        lastCall = Date.now()
        timeoutId = null
        fn(...args)
      }, remaining)
    }
  }
}

/**
 * Hook for debounced callback
 * Returns a memoized, debounced version of the callback
 */
export function useDebouncedCallback<T extends (...args: Parameters<T>) => ReturnType<T>>(
  callback: T,
  delay: number,
  deps: React.DependencyList = []
): (...args: Parameters<T>) => void {
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const callbackRef = useRef(callback)
  useEffect(() => {
    callbackRef.current = callback
    // deps spread is intentional for dynamic dependencies
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [callback, ...deps])
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
      }
    }
  }, [])
  return useCallback((...args: Parameters<T>) => {
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current)
    }
    timeoutRef.current = setTimeout(() => {
      callbackRef.current(...args)
    }, delay)
  }, [delay])
}

/**
 * Hook for throttled callback
 * Returns a memoized, throttled version of the callback
 */
export function useThrottledCallback<T extends (...args: Parameters<T>) => ReturnType<T>>(
  callback: T,
  interval: number,
  deps: React.DependencyList = []
): (...args: Parameters<T>) => void {
  const lastCallRef = useRef(0)
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const callbackRef = useRef(callback)
  useEffect(() => {
    callbackRef.current = callback
    // deps spread is intentional for dynamic dependencies
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [callback, ...deps])
  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
      }
    }
  }, [])
  return useCallback((...args: Parameters<T>) => {
    const now = Date.now()
    const remaining = interval - (now - lastCallRef.current)
    if (remaining <= 0) {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current)
        timeoutRef.current = null
      }
      lastCallRef.current = now
      callbackRef.current(...args)
    } else if (!timeoutRef.current) {
      timeoutRef.current = setTimeout(() => {
        lastCallRef.current = Date.now()
        timeoutRef.current = null
        callbackRef.current(...args)
      }, remaining)
    }
  }, [interval])
}

/**
 * Hook for debounced value
 * Returns a debounced version of the value that only updates after the delay
 */
export function useDebouncedValue<T>(value: T, delay: number): T {
  const [debouncedValue, setDebouncedValue] = useState(value)
  useEffect(() => {
    const timeoutId = setTimeout(() => {
      setDebouncedValue(value)
    }, delay)
    return () => {
      clearTimeout(timeoutId)
    }
  }, [value, delay])
  return debouncedValue
}

/**
 * Default debounce delays for different operations
 */
export const DEBOUNCE_DELAYS = {
  /** Validation debounce - short delay for quick feedback */
  validation: 150,
  /** Search debounce - medium delay for search operations */
  search: 250,
  /** API call debounce - longer delay for expensive operations */
  apiCall: 500,
  /** Save debounce - longest delay for save operations */
  save: 1000,
} as const
