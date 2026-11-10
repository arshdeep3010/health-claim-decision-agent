import { AnimatePresence, motion } from 'motion/react'
import { Cube } from './Cube'
import { STAGES, type PipelineStatus } from '../pipeline'

const STATUS_LABEL = { idle: 'Waiting', running: 'Running…', done: 'Done', skipped: 'Skipped', failed: 'Failed' }

export function Pipeline({ status }: { status: PipelineStatus }) {
  return (
    <ol className="pipeline" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
      {STAGES.map(stage => {
        const s = status[stage.id]
        const active = s.state !== 'idle'
        const finished = s.state === 'done' || s.state === 'skipped' || s.state === 'failed'
        const chipTone = s.state === 'failed' ? 'stop' : s.state === 'skipped' ? '' : finished ? s.tone ?? 'pass' : 'on'
        return (
          <li key={stage.id} className="stage" data-state={s.state} aria-live="polite">
            <div style={{ display: 'grid', placeItems: 'center', alignSelf: 'start', paddingTop: 4 }}>
              <Cube state={s.state} tone={s.tone} glyph={stage.glyph} />
            </div>
            <div className="stage-rail">
              <motion.div initial={false} animate={{ scaleY: finished ? 1 : 0 }}
                transition={{ type: 'spring', bounce: 0, duration: 0.7 }} />
            </div>
            <div style={{ minWidth: 0 }}>
              <div className="stage-head">
                <span className="stage-index">{stage.index}</span>
                <h3 className="stage-title">{stage.title}</h3>
                <span className="stage-status">{STATUS_LABEL[s.state]}</span>
              </div>
              <p className="stage-what">{stage.what}</p>
              <div className="stage-tech">
                {stage.tech.map(t => <span key={t} className={`chip ${active ? chipTone : ''}`}>{t}</span>)}
              </div>
              <AnimatePresence initial={false}>
                {s.result && (
                  <motion.div
                    key={s.result}
                    initial={{ opacity: 0, y: 6, filter: 'blur(4px)' }}
                    animate={{ opacity: 1, y: 0, filter: 'blur(0px)' }}
                    exit={{ opacity: 0 }}
                    transition={{ type: 'spring', bounce: 0, duration: 0.5 }}
                    className="stage-result"
                  >
                    <span>{s.result}</span>
                    {s.detail?.map(d => <span key={d} className="detail">{d}</span>)}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </li>
        )
      })}
    </ol>
  )
}
