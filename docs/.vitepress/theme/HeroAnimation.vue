<template>
  <div class="hero-anim">

    <!-- Layer 1: spacetime grid — rotates + tilts in 3D -->
    <svg class="layer-grid" viewBox="-100 0 400 400" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid slice">
      <g class="grid-travel">
        <g class="grid-spin">

          <!-- Base grid (always visible) -->
          <g class="grid-base">
            <!-- Horizontal -->
            <path d="M-100 30  Q 200 32  400 30"  fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.22"/>
            <path d="M-100 60  Q 200 64  400 60"  fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.25"/>
            <path d="M-100 340 Q 200 336 400 340" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.25"/>
            <path d="M-100 370 Q 200 368 400 370" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.22"/>
            <path d="M-100 90  Q 200 98  400 90"  fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.28"/>
            <path d="M-100 120 Q 200 134 400 120" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.32"/>
            <path d="M-100 280 Q 200 266 400 280" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.32"/>
            <path d="M-100 310 Q 200 302 400 310" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.28"/>
            <path d="M-100 150 Q 200 179 400 150" fill="none" stroke="#a5b4fc" stroke-width="0.75" opacity="0.38"/>
            <path d="M-100 170 Q 200 203 400 170" fill="none" stroke="#a5b4fc" stroke-width="0.80" opacity="0.42"/>
            <path d="M-100 185 Q 200 224 400 185" fill="none" stroke="#c4b5fd" stroke-width="0.85" opacity="0.48"/>
            <path d="M-100 215 Q 200 176 400 215" fill="none" stroke="#c4b5fd" stroke-width="0.85" opacity="0.48"/>
            <path d="M-100 230 Q 200 197 400 230" fill="none" stroke="#a5b4fc" stroke-width="0.80" opacity="0.42"/>
            <path d="M-100 250 Q 200 221 400 250" fill="none" stroke="#a5b4fc" stroke-width="0.75" opacity="0.38"/>
            <!-- Vertical -->
            <path d="M 30  0 Q 32  200 30  400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.22"/>
            <path d="M 60  0 Q 64  200 60  400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.25"/>
            <path d="M 340 0 Q 336 200 340 400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.25"/>
            <path d="M 370 0 Q 368 200 370 400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.22"/>
            <path d="M 90  0 Q 98  200 90  400" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.28"/>
            <path d="M 120 0 Q 134 200 120 400" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.32"/>
            <path d="M 280 0 Q 266 200 280 400" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.32"/>
            <path d="M 310 0 Q 302 200 310 400" fill="none" stroke="#818cf8" stroke-width="0.65" opacity="0.28"/>
            <path d="M 150 0 Q 179 200 150 400" fill="none" stroke="#a5b4fc" stroke-width="0.75" opacity="0.38"/>
            <path d="M 170 0 Q 203 200 170 400" fill="none" stroke="#a5b4fc" stroke-width="0.80" opacity="0.42"/>
            <path d="M 185 0 Q 224 200 185 400" fill="none" stroke="#c4b5fd" stroke-width="0.85" opacity="0.48"/>
            <path d="M 215 0 Q 176 200 215 400" fill="none" stroke="#c4b5fd" stroke-width="0.85" opacity="0.48"/>
            <path d="M 230 0 Q 197 200 230 400" fill="none" stroke="#a5b4fc" stroke-width="0.80" opacity="0.42"/>
            <path d="M 250 0 Q 221 200 250 400" fill="none" stroke="#a5b4fc" stroke-width="0.75" opacity="0.38"/>
          </g>

          <!-- Dense grid — fades in when close, out when far -->
          <g class="grid-dense">
            <!-- Horizontal in-between -->
            <path d="M-100 45  Q 200 48  400 45"  fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.18"/>
            <path d="M-100 75  Q 200 81  400 75"  fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.20"/>
            <path d="M-100 105 Q 200 116 400 105" fill="none" stroke="#818cf8" stroke-width="0.50" opacity="0.22"/>
            <path d="M-100 135 Q 200 157 400 135" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.26"/>
            <path d="M-100 160 Q 200 191 400 160" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.30"/>
            <path d="M-100 178 Q 200 214 400 178" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.32"/>
            <path d="M-100 222 Q 200 186 400 222" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.32"/>
            <path d="M-100 240 Q 200 209 400 240" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.30"/>
            <path d="M-100 265 Q 200 284 400 265" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.26"/>
            <path d="M-100 295 Q 200 304 400 295" fill="none" stroke="#818cf8" stroke-width="0.50" opacity="0.22"/>
            <path d="M-100 325 Q 200 319 400 325" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.20"/>
            <path d="M-100 355 Q 200 352 400 355" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.18"/>
            <!-- Vertical in-between -->
            <path d="M 45  0 Q 48  200 45  400" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.18"/>
            <path d="M 75  0 Q 81  200 75  400" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.20"/>
            <path d="M 105 0 Q 116 200 105 400" fill="none" stroke="#818cf8" stroke-width="0.50" opacity="0.22"/>
            <path d="M 135 0 Q 157 200 135 400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.26"/>
            <path d="M 160 0 Q 191 200 160 400" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.30"/>
            <path d="M 178 0 Q 214 200 178 400" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.32"/>
            <path d="M 222 0 Q 186 200 222 400" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.32"/>
            <path d="M 240 0 Q 209 200 240 400" fill="none" stroke="#a5b4fc" stroke-width="0.60" opacity="0.30"/>
            <path d="M 265 0 Q 284 200 265 400" fill="none" stroke="#818cf8" stroke-width="0.55" opacity="0.26"/>
            <path d="M 295 0 Q 304 200 295 400" fill="none" stroke="#818cf8" stroke-width="0.50" opacity="0.22"/>
            <path d="M 325 0 Q 319 200 325 400" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.20"/>
            <path d="M 355 0 Q 352 200 355 400" fill="none" stroke="#818cf8" stroke-width="0.45" opacity="0.18"/>
          </g>

        </g>
      </g>
    </svg>

    <!-- Layer 2: singularity — position fixed, size scales with depth -->
    <svg class="layer-center" viewBox="-100 0 400 400" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid slice">
      <defs>
        <filter id="f-glow" x="-60%" y="-60%" width="220%" height="220%">
          <feGaussianBlur stdDeviation="4" result="b"/>
          <feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <filter id="f-core" x="-150%" y="-150%" width="400%" height="400%">
          <feGaussianBlur stdDeviation="12" result="b"/>
          <feMerge><feMergeNode in="b"/><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge>
        </filter>
        <radialGradient id="core-grad" cx="40%" cy="38%" r="60%">
          <stop offset="0%"   stop-color="#f0e6ff"/>
          <stop offset="40%"  stop-color="#7c3aed"/>
          <stop offset="100%" stop-color="#1e1b4b"/>
        </radialGradient>
      </defs>

      <g class="center-scale">
        <!-- Gravitational waves -->
        <circle cx="200" cy="200" r="10" fill="none" stroke="#7c3aed" stroke-width="1.2" opacity="0">
          <animate attributeName="r"            values="10;185"  dur="5s" repeatCount="indefinite"/>
          <animate attributeName="opacity"      values="0.5;0"   dur="5s" repeatCount="indefinite"/>
          <animate attributeName="stroke-width" values="1.2;0.3" dur="5s" repeatCount="indefinite"/>
        </circle>
        <circle cx="200" cy="200" r="10" fill="none" stroke="#7c3aed" stroke-width="1.2" opacity="0">
          <animate attributeName="r"            values="10;185"  dur="5s" begin="1.7s" repeatCount="indefinite"/>
          <animate attributeName="opacity"      values="0.5;0"   dur="5s" begin="1.7s" repeatCount="indefinite"/>
          <animate attributeName="stroke-width" values="1.2;0.3" dur="5s" begin="1.7s" repeatCount="indefinite"/>
        </circle>
        <circle cx="200" cy="200" r="10" fill="none" stroke="#7c3aed" stroke-width="1.2" opacity="0">
          <animate attributeName="r"            values="10;185"  dur="5s" begin="3.4s" repeatCount="indefinite"/>
          <animate attributeName="opacity"      values="0.5;0"   dur="5s" begin="3.4s" repeatCount="indefinite"/>
          <animate attributeName="stroke-width" values="1.2;0.3" dur="5s" begin="3.4s" repeatCount="indefinite"/>
        </circle>
        <!-- Corona -->
        <circle cx="200" cy="200" r="17" fill="#7c3aed" opacity="0.3" filter="url(#f-core)"/>
        <!-- Core -->
        <circle cx="200" cy="200" r="12" fill="url(#core-grad)" filter="url(#f-glow)"/>
        <!-- Specular — upper-left, clear of text -->
        <circle cx="194" cy="193" r="2" fill="white" opacity="0.35"/>
        <!-- Label — centered in lower half of sphere -->
        <text x="200" y="205" text-anchor="middle"
              font-family="'Inter', system-ui, sans-serif"
              font-size="9" font-weight="800" fill="white" opacity="0.90">Jo</text>
      </g>
    </svg>

  </div>
</template>

<style scoped>
.hero-anim { position: relative; width: 100%; height: 100%; opacity: 0.6; }

.layer-grid,
.layer-center {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.grid-travel {
  transform-box: fill-box;
  transform-origin: center;
  animation: travel 22s ease-in-out infinite;
}
.grid-spin {
  transform-box: fill-box;
  transform-origin: center;
  animation: spin 55s linear infinite;
}

/* Dense lines fade in when close, fade out when far */
.grid-dense {
  animation: density 22s ease-in-out infinite;
}

/* Singularity — fully fixed, no transform */
.center-scale {}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes travel {
  0%   { transform: perspective(500px) rotateX(0deg)   rotateY(0deg);   }
  15%  { transform: perspective(500px) rotateX(30deg)  rotateY(0deg);   }
  35%  { transform: perspective(500px) rotateX(12deg)  rotateY(28deg);  }
  55%  { transform: perspective(500px) rotateX(-22deg) rotateY(30deg);  }
  75%  { transform: perspective(500px) rotateX(-30deg) rotateY(-12deg); }
  90%  { transform: perspective(500px) rotateX(-8deg)  rotateY(-26deg); }
  100% { transform: perspective(500px) rotateX(0deg)   rotateY(0deg);   }
}

@keyframes density {
  0%   { opacity: 0.5; }
  15%  { opacity: 1.0; }
  35%  { opacity: 0.0; }
  55%  { opacity: 0.9; }
  75%  { opacity: 0.0; }
  90%  { opacity: 0.7; }
  100% { opacity: 0.5; }
}

</style>
