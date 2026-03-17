<template>
  <div class="hero-anim">
    <svg viewBox="0 0 400 400" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <filter id="glow" x="-60%" y="-60%" width="220%" height="220%">
          <feGaussianBlur stdDeviation="3.5" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <filter id="glow-strong" x="-100%" y="-100%" width="300%" height="300%">
          <feGaussianBlur stdDeviation="8" result="blur"/>
          <feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <linearGradient id="g1" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#7c3aed"/>
          <stop offset="100%" stop-color="#2563eb"/>
        </linearGradient>
        <linearGradient id="g2" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stop-color="#a78bfa"/>
          <stop offset="100%" stop-color="#60a5fa"/>
        </linearGradient>
        <radialGradient id="core" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#c4b5fd"/>
          <stop offset="60%" stop-color="#7c3aed"/>
          <stop offset="100%" stop-color="#2563eb" stop-opacity="0.8"/>
        </radialGradient>
        <radialGradient id="bg-glow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#7c3aed" stop-opacity="0.12"/>
          <stop offset="100%" stop-color="#7c3aed" stop-opacity="0"/>
        </radialGradient>
      </defs>

      <!-- Ambient background glow -->
      <circle cx="200" cy="200" r="190" fill="url(#bg-glow)" class="pulse-bg"/>

      <!-- Outer ring — slow clockwise dashes -->
      <circle cx="200" cy="200" r="165" fill="none"
              stroke="url(#g1)" stroke-width="1" stroke-dasharray="6 14"
              opacity="0.35" class="ring-cw-slow"/>

      <!-- Second ring — medium counter-clockwise -->
      <circle cx="200" cy="200" r="130" fill="none"
              stroke="url(#g2)" stroke-width="1.5" stroke-dasharray="3 9"
              opacity="0.45" class="ring-ccw-med"/>

      <!-- Hexagon — slow counter-clockwise -->
      <polygon points="200,75 280,117.5 280,202.5 200,245 120,202.5 120,117.5"
               fill="none" stroke="url(#g1)" stroke-width="1.8"
               filter="url(#glow)" opacity="0.6" class="ring-ccw-slow"/>

      <!-- Inner spinning triangle -->
      <polygon points="200,115 258,215 142,215"
               fill="none" stroke="#a78bfa" stroke-width="1.5"
               filter="url(#glow)" opacity="0.65" class="ring-cw-med"/>

      <!-- Spokes from center to hex vertices — animated dash flow -->
      <line x1="200" y1="200" x2="200" y2="75"    stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-1"/>
      <line x1="200" y1="200" x2="280" y2="117.5"  stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-2"/>
      <line x1="200" y1="200" x2="280" y2="202.5"  stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-3"/>
      <line x1="200" y1="200" x2="200" y2="245"    stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-1"/>
      <line x1="200" y1="200" x2="120" y2="202.5"  stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-2"/>
      <line x1="200" y1="200" x2="120" y2="117.5"  stroke="url(#g1)" stroke-width="1" opacity="0.25" stroke-dasharray="5 7" class="spoke-flow-3"/>

      <!-- Orbiting capability nodes on outer ring (r=165) -->
      <g class="orbit-cw-6s">
        <circle cx="200" cy="35"  r="7" fill="#a78bfa" filter="url(#glow)"/>
        <text x="200" y="20" text-anchor="middle" font-family="monospace" font-size="8" fill="#a78bfa" opacity="0.7">IO</text>
      </g>
      <g class="orbit-ccw-9s">
        <circle cx="200" cy="35" r="5" fill="#60a5fa" filter="url(#glow)"/>
        <text x="200" y="20" text-anchor="middle" font-family="monospace" font-size="8" fill="#60a5fa" opacity="0.7">Net</text>
      </g>
      <g class="orbit-cw-13s">
        <circle cx="200" cy="35" r="6" fill="#34d399" filter="url(#glow)"/>
        <text x="200" y="20" text-anchor="middle" font-family="monospace" font-size="8" fill="#34d399" opacity="0.7">DB</text>
      </g>
      <g class="orbit-ccw-7s">
        <circle cx="200" cy="35" r="4.5" fill="#f472b6" filter="url(#glow)"/>
        <text x="200" y="20" text-anchor="middle" font-family="monospace" font-size="8" fill="#f472b6" opacity="0.7">FS</text>
      </g>

      <!-- Mid-ring orbiting nodes (r=130) -->
      <g class="orbit-cw-8s">
        <circle cx="200" cy="70" r="5.5" fill="#818cf8" filter="url(#glow)"/>
      </g>
      <g class="orbit-ccw-11s">
        <circle cx="200" cy="70" r="4" fill="#38bdf8" filter="url(#glow)"/>
      </g>

      <!-- Core pulse ring -->
      <circle cx="200" cy="200" r="45" fill="none"
              stroke="url(#g1)" stroke-width="2" filter="url(#glow-strong)"
              class="pulse-ring"/>

      <!-- Core -->
      <circle cx="200" cy="200" r="32" fill="url(#core)" filter="url(#glow-strong)"/>

      <!-- "Jo" text in core -->
      <text x="200" y="207" text-anchor="middle"
            font-family="'Inter', system-ui, sans-serif"
            font-size="20" font-weight="800" fill="white" opacity="0.92"
            filter="url(#glow)">Jo</text>

      <!-- Inner core highlight -->
      <circle cx="192" cy="192" r="10" fill="white" opacity="0.2"/>
    </svg>
  </div>
</template>

<style scoped>
.hero-anim {
  width: 340px;
  height: 340px;
}
.hero-anim svg {
  width: 100%;
  height: 100%;
}

.ring-cw-slow    { transform-origin: 200px 200px; animation: cw  22s linear infinite; }
.ring-ccw-slow   { transform-origin: 200px 200px; animation: ccw 18s linear infinite; }
.ring-cw-med     { transform-origin: 200px 200px; animation: cw   9s linear infinite; }
.ring-ccw-med    { transform-origin: 200px 200px; animation: ccw 13s linear infinite; }

.orbit-cw-6s     { transform-origin: 200px 200px; animation: cw   6s linear infinite; }
.orbit-ccw-9s    { transform-origin: 200px 200px; animation: ccw  9s linear infinite; }
.orbit-cw-13s    { transform-origin: 200px 200px; animation: cw  13s linear infinite; }
.orbit-ccw-7s    { transform-origin: 200px 200px; animation: ccw  7s linear infinite; }
.orbit-cw-8s     { transform-origin: 200px 200px; animation: cw   8s linear infinite; }
.orbit-ccw-11s   { transform-origin: 200px 200px; animation: ccw 11s linear infinite; }

.spoke-flow-1 { animation: dashFlow 2.4s linear infinite; }
.spoke-flow-2 { animation: dashFlow 2.4s linear infinite 0.8s; }
.spoke-flow-3 { animation: dashFlow 2.4s linear infinite 1.6s; }

.pulse-ring { animation: pulseRing 3s ease-in-out infinite; }
.pulse-bg   { animation: pulseBg  4s ease-in-out infinite alternate; }

@keyframes cw  { to { transform: rotate( 360deg); } }
@keyframes ccw { to { transform: rotate(-360deg); } }

@keyframes dashFlow {
  from { stroke-dashoffset: 24; }
  to   { stroke-dashoffset:  0; }
}
@keyframes pulseRing {
  0%, 100% { r: 45; opacity: 0.8; }
  50%       { r: 52; opacity: 0.3; }
}
@keyframes pulseBg {
  from { opacity: 0.6; }
  to   { opacity: 1.0; }
}
</style>
