import { useEffect, useRef } from 'react'
import { animate, motion, useMotionValue, useReducedMotion, type AnimationPlaybackControls } from 'motion/react'
import type { StageState, Tone } from '../pipeline'

interface Props { state: StageState; tone?: Tone; glyph: string; size?: number }

const TILT_X = -16
const FRONT_Y = 28            // resting angle showing the glyph face plus one side
const RESULT_Y = 180 + FRONT_Y // the result face sits on the back of the cube

/** Smallest forward rotation from `from` that lands on `target` (mod 360). */
function forwardTo(from: number, target: number, minTurn = 120) {
  let delta = (((target - from) % 360) + 360) % 360
  if (delta < minTurn) delta += 360
  return from + delta
}

/**
 * A 3D "checkbox": a six-faced CSS cube. Idle shows the stage glyph, running
 * spins it, and a finished state springs it forward onto its result face.
 * Animations always start from the live on-screen angle, so state changes can
 * interrupt each other without jumps.
 */
export function Cube({ state, tone = 'pass', glyph, size = 56 }: Props) {
  const reduce = useReducedMotion()
  const ry = useMotionValue(FRONT_Y)
  const rx = useMotionValue(TILT_X)
  const spin = useRef<AnimationPlaybackControls | null>(null)
  const finished = state === 'done' || state === 'failed' || state === 'skipped'

  useEffect(() => {
    spin.current?.stop()
    if (reduce) { ry.set(finished ? RESULT_Y : FRONT_Y); return }
    if (state === 'running') {
      const start = ry.get()
      spin.current = animate(ry, start + 360, { duration: 1.5, ease: 'linear', repeat: Infinity })
      animate(rx, [TILT_X, -28, TILT_X], { duration: 1.5, ease: 'easeInOut', repeat: Infinity })
    } else if (finished) {
      animate(ry, forwardTo(ry.get(), RESULT_Y), { type: 'spring', bounce: 0.28, duration: 0.9 })
      animate(rx, TILT_X, { type: 'spring', bounce: 0, duration: 0.5 })
    } else {
      animate(ry, forwardTo(ry.get(), FRONT_Y, 0), { type: 'spring', bounce: 0, duration: 0.6 })
      animate(rx, TILT_X, { type: 'spring', bounce: 0, duration: 0.5 })
    }
    return () => spin.current?.stop()
  }, [state, finished, reduce, rx, ry])

  const half = size / 2
  const faceTone: Tone = state === 'failed' ? 'stop' : tone
  const symbol = state === 'skipped' ? 'dash' : state === 'failed' || faceTone === 'stop' ? 'cross' : faceTone === 'warn' ? 'alert' : 'check'

  return (
    <div className="cube-scene" style={{ width: size, height: size, position: 'relative' }} aria-hidden>
      <motion.div
        className="cube"
        data-state={state}
        data-tone={finished ? faceTone : undefined}
        style={{ width: size, height: size, rotateX: rx, rotateY: ry }}
      >
        <div className="face face-front" style={{ transform: `translateZ(${half}px)`, fontSize: size * 0.24 }}>
          {glyph}
        </div>
        <div className="face face-result" style={{ transform: `rotateY(180deg) translateZ(${half}px)` }}>
          {finished && <Symbol kind={symbol} size={size * 0.5} />}
        </div>
        <div className="face face-side" style={{ transform: `rotateY(90deg) translateZ(${half}px)` }} />
        <div className="face face-side" style={{ transform: `rotateY(-90deg) translateZ(${half}px)` }} />
        <div className="face face-side" style={{ transform: `rotateX(90deg) translateZ(${half}px)`, filter: 'brightness(1.12)' }} />
        <div className="face face-side" style={{ transform: `rotateX(-90deg) translateZ(${half}px)`, filter: 'brightness(0.85)' }} />
      </motion.div>
      <div className="cube-shadow" />
    </div>
  )
}

function Symbol({ kind, size }: { kind: 'check' | 'cross' | 'alert' | 'dash'; size: number }) {
  const draw = { initial: { pathLength: 0 }, animate: { pathLength: 1 }, transition: { duration: 0.45, delay: 0.35, ease: [0.65, 0, 0.35, 1] as const } }
  const common = { fill: 'none', stroke: '#fff', strokeWidth: 3.4, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  return (
    <svg width={size} height={size} viewBox="0 0 24 24">
      {kind === 'check' && <motion.path d="M5 12.5l4.5 4.5L19 7.5" {...common} {...draw} />}
      {kind === 'cross' && <>
        <motion.path d="M7 7l10 10" {...common} {...draw} />
        <motion.path d="M17 7L7 17" {...common} {...draw} />
      </>}
      {kind === 'alert' && <>
        <motion.path d="M12 6v8" {...common} {...draw} />
        <motion.path d="M12 18.2v.1" {...common} {...draw} />
      </>}
      {kind === 'dash' && <motion.path d="M7 12h10" {...common} {...draw} />}
    </svg>
  )
}
